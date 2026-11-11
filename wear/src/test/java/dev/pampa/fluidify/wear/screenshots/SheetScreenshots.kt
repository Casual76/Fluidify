package dev.pampa.fluidify.wear.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.VolumeControl
import dev.pampa.fluidify.wear.protocol.ContextPage
import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.protocol.DeviceList
import dev.pampa.fluidify.wear.protocol.LibraryItem
import dev.pampa.fluidify.wear.protocol.LibraryKind
import dev.pampa.fluidify.wear.protocol.LibraryPage
import dev.pampa.fluidify.wear.protocol.LibraryShelf
import dev.pampa.fluidify.wear.protocol.QueueEntry
import dev.pampa.fluidify.wear.protocol.QueueWindow
import dev.pampa.fluidify.wear.protocol.SleepInfo
import dev.pampa.fluidify.wear.ui.browse.ContextScreen
import dev.pampa.fluidify.wear.ui.browse.HomeScreen
import dev.pampa.fluidify.wear.ui.browse.LibraryScreen
import dev.pampa.fluidify.wear.ui.sheets.EssentialsScreen
import dev.pampa.fluidify.wear.ui.sheets.OutputScreen
import dev.pampa.fluidify.wear.ui.sheets.QueueScreen
import dev.pampa.fluidify.wear.ui.sheets.SleepScreen
import dev.pampa.fluidify.wear.ui.sheets.VolumeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The screens that open on top of the player, and the library. See [PlayerScreenshots]. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = Watch44)
class SheetScreenshots {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: WearApp
    private lateinit var art: ArtStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        installSynchronousImageLoader(app)
        art = ArtStore(app)
        storeSampleCover(art)
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { WatchFrame { content() } }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private val queue = QueueWindow(
        currentIndex = 1,
        total = 5,
        items = listOf(
            QueueEntry(0, "spotify:track:0", "Alba di vetro", "Aurora Viola"),
            QueueEntry(1, "spotify:track:1", "Notturno sul lago", "Aurora Viola", artKey = SampleArtKey),
            QueueEntry(2, "spotify:track:2", "Correnti", "Marea"),
            QueueEntry(3, "spotify:track:3", "Settembre", "Le Onde"),
            QueueEntry(4, "spotify:track:4", "Polvere di stelle", "Cometa"),
        ),
    )

    @Test
    fun queue() = capture("queue") {
        QueueScreen(FakeControls(sampleSnapshot()), art, load = { Result.success(queue) }, onPlayed = {})
    }

    @Test
    fun output() = capture("output") {
        OutputScreen(
            FakeControls(sampleSnapshot()),
            load = {
                Result.success(
                    DeviceList(
                        devices = listOf(
                            DeviceInfo("phone", "Galaxy S25", DeviceKind.PHONE, isThisPhone = true),
                            DeviceInfo("pc", "PC di casa", DeviceKind.COMPUTER),
                            DeviceInfo("tv", "TV del salotto", DeviceKind.TV),
                            DeviceInfo("spk", "Cassa cucina", DeviceKind.SPEAKER),
                        ),
                        activeId = "phone",
                    ),
                )
            },
            onChosen = {},
            watchOutputs = listOf(
                dev.pampa.fluidify.wear.standalone.LocalOutput(1, "Galaxy Buds3 Pro", dev.pampa.fluidify.wear.standalone.LocalOutput.Kind.HEADPHONES),
                dev.pampa.fluidify.wear.standalone.LocalOutput(2, "", dev.pampa.fluidify.wear.standalone.LocalOutput.Kind.SPEAKER),
            ),
            onConnectHeadphones = {},
        )
    }

    @Test
    fun sleep() = capture("sleep") {
        SleepScreen(FakeControls(sampleSnapshot().copy(sleep = SleepInfo(endsAtEpochMs = Now + 25 * 60_000L))), onSet = {})
    }

    @Test
    fun essentials() = capture("essentials") {
        val snapshot = sampleSnapshot()
        EssentialsScreen(
            FakeControls(snapshot.copy(track = snapshot.track!!.copy(artistUri = "spotify:artist:x", albumUri = "spotify:album:y", album = "Riflessi"))),
            onQueue = {},
            onSleep = {},
            onOpenContext = { _, _ -> },
            onRadio = {},
        )
    }

    @Test
    fun volume() = capture("volume") {
        val controls = FakeControls(sampleSnapshot())
        VolumeScreen(controls, VolumeControl(CoroutineScope(Dispatchers.Unconfined), controls).apply { sync(0.62f) })
    }

    @Test
    fun library() = capture("library") { LibraryScreen(onSection = { _, _ -> }, onWatchDownloads = {}) }

    @Test
    fun watchDownloads() {
        val credential = java.io.File(app.filesDir, "librespot/reusable/credentials.json")
        credential.parentFile!!.mkdirs()
        credential.writeText("""{"auth_type":1,"username":"synthetic-preview","auth_data":"AQ=="}""")
        // A screenshot owns its synthetic authority and scope; no process-lifetime file watcher
        // may outlive Robolectric's temporary application directory.
        val authority = dev.pampa.fluidify.wear.downloads.WatchDownloadAuthority(
            java.io.File(app.noBackupFilesDir, "synthetic-watch-download-authority.json"), credential)
        val sessions = dev.pampa.fluidify.wear.link.PhoneAccountSession(app.getSharedPreferences("synthetic-watch-download-session", 0))
        sessions.accept(dev.pampa.fluidify.wear.protocol.AccountState(
            true, "synthetic-preview", System.currentTimeMillis(), "synthetic-download-session",
            dev.pampa.fluidify.wear.downloads.WatchDownloadAuthority.namespace("synthetic-preview")))
        val previewScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val downloads = dev.pampa.fluidify.wear.downloads.WatchDownloads(app, authority, sessions, previewScope,
            enqueue = { _, _ -> }, publishTransport = {})
        try {
            check(authority.confirm("synthetic-preview", authority.startTicket()))
            val access = authority.capture()!!
            downloads.keep(access, "spotify:playlist:a", "Corsa del mattino")
            downloads.store.forAccount(access).setTracks("spotify:playlist:a", List(24) { "spotify:track:00000000000000000000%02d".format(it) })
            downloads.keep(access, "spotify:album:b", "Riflessi")
            downloads.changed(schedule = false)
            capture("watch_downloads") { dev.pampa.fluidify.wear.ui.browse.WatchDownloadsScreen(downloads, onOpen = { _, _ -> }) }
        } finally {
            previewScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun home() {
        app.link.accountSessions.accept(dev.pampa.fluidify.wear.protocol.AccountState(
            true, "synthetic-preview", localDataSession = "synthetic-preview-session", localDataNamespace = "synthetic-preview"))
        app.library.seed(
            home = LibraryPage(
                listOf(
                    LibraryShelf(
                        "",
                        listOf(
                            LibraryItem("spotify:playlist:a", "Notturni", kind = LibraryKind.PLAYLIST, artKey = SampleArtKey, pinned = true),
                            LibraryItem("spotify:user:x:collection", "Brani che ti piacciono", kind = LibraryKind.LIKED),
                            LibraryItem("spotify:playlist:b", "In macchina", kind = LibraryKind.PLAYLIST),
                        ),
                    ),
                    LibraryShelf(
                        "Creati per te",
                        listOf(LibraryItem("spotify:playlist:c", "Daily Mix 1"), LibraryItem("spotify:playlist:d", "Discover Weekly")),
                    ),
                ),
            ),
        )
        capture("home") { HomeScreen(app, load = true, onSearch = {}, onLibrary = {}, onOpen = { _, _ -> }) }
    }

    @Test
    fun context() {
        app.link.accountSessions.accept(dev.pampa.fluidify.wear.protocol.AccountState(
            true, "synthetic-preview", localDataSession = "synthetic-preview-session", localDataNamespace = "synthetic-preview"))
        app.library.seed(
            contexts = listOf(
                ContextPage(
                    uri = "spotify:playlist:a",
                    title = "Notturni",
                    artKey = SampleArtKey,
                    tracks = queue.items.map { LibraryItem(it.uri, it.title, it.artist, LibraryKind.TRACK, artKey = it.artKey) },
                ),
            ),
        )
        capture("context") { ContextScreen(app, "spotify:playlist:a", "Notturni", onPlaying = {}) }
    }

    @Test
    fun addToPlaylist() {
        app.link.accountSessions.accept(dev.pampa.fluidify.wear.protocol.AccountState(
            true, "synthetic-preview", localDataSession = "synthetic-preview-session", localDataNamespace = "synthetic-preview"))
        app.library.seed(
            sections = mapOf(
                dev.pampa.fluidify.wear.protocol.LibrarySection.PLAYLISTS to LibraryPage(
                    listOf(
                        LibraryShelf(
                            "",
                            listOf(
                                LibraryItem("spotify:playlist:1", "Corsa del mattino", artKey = SampleArtKey, editable = true),
                                LibraryItem("spotify:playlist:2", "Top 50 Italia", editable = false),
                                LibraryItem("spotify:playlist:3", "Viaggio in treno", editable = true),
                                LibraryItem("spotify:playlist:4", "Cena con amici"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        capture("add_to_playlist") {
            dev.pampa.fluidify.wear.ui.browse.AddToPlaylistScreen(app, "spotify:track:1", "Notturno sul lago", onDone = {})
        }
    }

    @Test
    fun unlikeDialog() = capture("unlike_dialog") {
        dev.pampa.fluidify.wear.ui.player.UnlikeDialog(visible = true, title = "Notturno sul lago", onConfirm = {}, onDismiss = {})
    }
}
