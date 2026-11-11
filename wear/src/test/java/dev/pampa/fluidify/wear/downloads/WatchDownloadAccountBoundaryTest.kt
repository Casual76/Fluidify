package dev.pampa.fluidify.wear.downloads

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.pampa.fluidify.wear.link.PhoneAccountSession
import dev.pampa.fluidify.wear.protocol.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared only. Production plan/status/storage/dispatch entrances; all identity, transport and audio are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WatchDownloadAccountBoundaryTest {
    private val track = "spotify:track:5sWHDYs0csV6RS48xBl0tH"
    private val owner = "spotify:playlist:p"
    private class Fixture(scope: CoroutineScope) {
        private val base = ApplicationProvider.getApplicationContext<Context>()
        val dir = Files.createTempDirectory(base.cacheDir.toPath(), "download-boundary-").toFile()
        private val unique = UUID.randomUUID().toString()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(dir, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$unique-$name", mode)
        }
        val credential = File(context.filesDir, "librespot/reusable/credentials.json")
        val receipt = File(context.noBackupFilesDir, "receipt.json")
        val authority = WatchDownloadAuthority(receipt, credential)
        val sessions = PhoneAccountSession(context.getSharedPreferences("phone", 0))
        val queued = mutableListOf<Pair<String, String?>>()
        val sent = mutableListOf<WatchDownloadsStatus>()
        val downloads = WatchDownloads(context, authority, sessions, scope,
            enqueue = { namespace, session -> queued += namespace to session }, publishTransport = { sent += it })
        private var at = 0L
        fun phone(canonical: String, session: String): PhoneAccountSession.Scope {
            sessions.accept(AccountState(true, canonical, ++at, session, WatchDownloadAuthority.namespace(canonical)))
            downloads.onPhoneAccount(explicit = true)
            return sessions.capture()!!
        }
        fun native(canonical: String): WatchDownloadAuthority.Access {
            credential.parentFile!!.mkdirs()
            credential.writeText("""{"auth_type":1,"username":"$canonical","auth_data":"AQID"}""")
            assertTrue(authority.confirm(canonical, authority.startTicket()))
            return authority.capture()!!
        }
        fun files() = downloads.store.forAccount(authority.capture()!!)
    }
    private fun sidecar() = """{"uri":"$track","format":"OGG_VORBIS_160","name":"Synthetic song","artists":[]}"""
    private fun arrive(files: WatchDownloadFiles) {
        files.partFile(track)!!.apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(files.adopt(track, sidecar()))
    }

    @Test fun phonePlanIsNotALocalWatchIdentityAndLegacyOrStalePlansAreDenied() = runTest {
        val f = Fixture(backgroundScope)
        val phone = f.phone("synthetic-A", "a-1")
        assertFalse(f.downloads.onRequest(DownloadRequest(owner, "legacy")))
        assertTrue(f.downloads.onRequest(DownloadRequest(owner, "A", accountSession = phone.session, accountNamespace = phone.namespace)))
        assertNull(f.authority.capture())
        assertTrue(f.downloads.store.owners.value.isEmpty())
        assertEquals(phone.namespace, f.queued.single().first)
        val access = f.native("synthetic-A")
        assertEquals("A", f.downloads.store.owners.value.single().title)
        f.phone("synthetic-B", "b-1")
        assertTrue(f.downloads.store.owners.value.isEmpty())
        assertFalse(f.downloads.onRequest(DownloadRequest(owner, "late A", accountSession = phone.session, accountNamespace = phone.namespace)))
        assertFalse(f.authority.isCurrent(access))
    }

    @Test fun aToBToARetainsFilesAndDatesAndOfflineRestartNeedsNoPhoneCorrelation() = runTest {
        val f = Fixture(backgroundScope)
        f.phone("synthetic-A", "a-1")
        val a = f.native("synthetic-A")
        f.downloads.keep(a, owner, "A")
        val old = f.files()
        old.setTracks(owner, listOf(track))
        arrive(old)
        val bytes = old.audioFile(track)!!.readBytes()
        val date = old.owners.value.single().addedAtEpochMs
        f.phone("synthetic-B", "b-1")
        val b = f.native("synthetic-B")
        assertTrue(f.downloads.store.offlineTracks(owner).isEmpty())
        f.downloads.keep(b, owner, "B")
        f.files().setTracks(owner, listOf(track))
        assertFalse(f.files().has(track))
        try { old.setTracks(owner, emptyList()); fail("Stale response admitted") } catch (_: CancellationException) { }
        f.phone("synthetic-A", "a-2")
        f.native("synthetic-A")
        assertEquals("A", f.downloads.store.owners.value.single().title)
        assertEquals(date, f.downloads.store.owners.value.single().addedAtEpochMs)
        assertArrayEquals(bytes, f.files().audioFile(track)!!.readBytes())
        f.sessions.clear() // Losing phone correlation does not invent or revoke local watch identity.
        val restarted = WatchDownloadAuthority(f.receipt, f.credential)
        val offline = WatchDownloadStore(f.context, restarted)
        assertEquals("Synthetic song", offline.offlineTracks(owner).single().name)
        assertEquals(a.namespace, restarted.capture()!!.namespace)
        assertTrue(File(f.context.filesDir, "downloads/accounts/${b.namespace}/owners.json").isFile)
    }

    @Test fun explicitLegacyClaimKeepsOriginalBytesAndNeverAssignsOnFirstLogin() = runTest {
        val f = Fixture(backgroundScope)
        val legacy = File(f.context.filesDir, "downloads").apply { mkdirs() }
        File(legacy, "owners.json").writeText(WearCodec.json.encodeToString(ListSerializer(KeptOwner.serializer()),
            listOf(KeptOwner(owner, "Unknown", tracks = listOf(track), addedAtEpochMs = 42))))
        val hex = SpotifyIds.trackHex(track)!!
        val legacyAudio = File(legacy, "audio/${hex.take(2)}/${hex.drop(2)}").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(8, 9))
        }
        File(legacy, "meta/${hex.take(2)}/${hex.drop(2)}.json").apply { parentFile!!.mkdirs(); writeText(sidecar()) }
        f.phone("synthetic-A", "a-1")
        val a = f.native("synthetic-A")
        assertTrue(f.downloads.store.hasLegacy())
        assertTrue(f.downloads.store.owners.value.isEmpty())
        assertFalse(f.files().has(track))
        f.downloads.store.claimLegacy(a)
        assertEquals(42L, f.downloads.store.owners.value.single().addedAtEpochMs)
        assertTrue(File(legacy, "owners.json").isFile)
        assertTrue(f.files().has(track))
        assertArrayEquals(legacyAudio.readBytes(), f.files().audioFile(track)!!.readBytes())
        f.phone("synthetic-B", "b-1")
        val b = f.native("synthetic-B")
        assertTrue(f.downloads.store.owners.value.isEmpty())
        assertFalse(f.downloads.store.hasLegacy())
        try { f.downloads.store.claimLegacy(b); fail("Legacy reassigned") } catch (_: IllegalStateException) { }
    }

    @Test fun revokedReceiptAndLateNativeVerificationCannotRestoreFromKeptCredential() = runTest {
        val f = Fixture(backgroundScope)
        f.phone("synthetic-A", "a-1")
        f.native("synthetic-A")
        val ticket = f.authority.startTicket()
        f.authority.revoke()
        assertFalse(f.authority.confirm("synthetic-A", ticket))
        assertNull(WatchDownloadAuthority(f.receipt, f.credential).capture())
        f.receipt.writeText("corrupted")
        assertNull(WatchDownloadAuthority(f.receipt, f.credential).capture())
    }

    @Test fun nativeDispatchDeniesLocalOnlyOrWrongNativeAccountAndChecksRealReturn() = runTest {
        val f = Fixture(backgroundScope)
        f.phone("synthetic-A", "a-1")
        val a = f.native("synthetic-A")
        var ready = false
        var canonical = "synthetic-A"
        var calls = 0
        var root = ""
        val dispatch = WatchDownloadDispatch(f.authority, { ready }, { canonical }, { root = it }, f.context.filesDir)
        try { dispatch.run(a) { calls++ }; fail("Offline dispatch") } catch (_: IllegalStateException) { }
        ready = true; canonical = "synthetic-B"
        try { dispatch.run(a) { calls++ }; fail("Wrong native account") } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
        canonical = "synthetic-A"
        try { dispatch.run(a) { calls++; f.authority.revoke() }; fail("Revoked return published") } catch (_: CancellationException) { }
        assertEquals(1, calls)
        assertTrue(root.replace('\\', '/').endsWith("downloads/accounts/${a.namespace}"))
    }

    @Test fun finalFileIngressRefusesLatePhoneHeaderAndRevokedLocalHandle() = runTest {
        val f = Fixture(backgroundScope)
        val phone = f.phone("synthetic-A", "a-1")
        val a = f.native("synthetic-A")
        val files = f.files()
        files.partFile(track)!!.apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val commit = WatchPhoneFileCommit(f.sessions, phone, f.authority, a, files)
        assertFalse(commit.adopt(track, FileHeader(true, sidecar(), 3, accountSession = "wrong")))
        f.phone("synthetic-B", "b-1")
        assertFalse(commit.adopt(track, FileHeader(true, sidecar(), 3, accountSession = "a-1")))
        f.phone("synthetic-A", "a-2")
        f.native("synthetic-A")
        assertFalse(files.root.resolve("meta").exists())
    }

    @Test fun publishedInventoryUsesOnlyMatchingLocalIdentityAndCurrentPhoneSession() = runTest {
        val f = Fixture(backgroundScope)
        f.phone("synthetic-A", "a-1")
        val a = f.native("synthetic-A")
        f.downloads.keep(a, owner, "A")
        f.downloads.publish(final = true)
        assertEquals("A", f.sent.last().owners.single().title)
        assertEquals("a-1", f.sent.last().accountSession)
        f.phone("synthetic-B", "b-1")
        f.sent.clear()
        f.downloads.publish(final = true)
        assertTrue(f.sent.isEmpty())
        f.native("synthetic-B")
        f.downloads.publish(final = true)
        assertTrue(f.sent.last().owners.isEmpty())
        assertEquals("b-1", f.sent.last().accountSession)
        assertEquals(WatchDownloadAuthority.namespace("synthetic-B"), f.sent.last().accountNamespace)
    }

    @Test fun duplicatePlanIdCannotReapplyAnOlderKeepAfterANewerDrop() = runTest {
        val f = Fixture(backgroundScope)
        val phone = f.phone("synthetic-A", "a-1")
        f.native("synthetic-A")
        val keep = DownloadRequest(owner, "A", accountSession = phone.session, accountNamespace = phone.namespace, requestId = "keep")
        val drop = keep.copy(keep = false, requestId = "drop")
        assertTrue(f.downloads.onRequest(keep))
        assertTrue(f.downloads.onRequest(drop))
        assertTrue(f.downloads.onRequest(keep))
        assertTrue(f.downloads.store.owners.value.isEmpty())
    }

    @Test fun failedManifestCommitIsNotAcknowledgedOrPublishedAsKept() = runTest {
        val f = Fixture(backgroundScope)
        val phone = f.phone("synthetic-A", "a-1")
        f.native("synthetic-A")
        File(f.files().root, "owners.json").apply {
            assertTrue(mkdirs())
            resolve("synthetic-blocker").writeText("occupied")
        } // A nonempty directory cannot be replaced by a manifest on the host filesystem.
        assertFalse(f.downloads.onRequest(DownloadRequest(owner, "A", accountSession = phone.session,
            accountNamespace = phone.namespace, requestId = "failed")))
        assertTrue(f.downloads.store.owners.value.isEmpty())
        assertEquals(WatchDownloads.PAUSED_STORAGE, f.downloads.status.value.paused)
    }

    @Test fun interruptedLegacyCopyReservesItsExplicitOwnerAndCanResumeOnlyThere() = runTest {
        val f = Fixture(backgroundScope)
        val legacy = File(f.context.filesDir, "downloads").apply { mkdirs() }
        File(legacy, "owners.json").writeText(WearCodec.json.encodeToString(ListSerializer(KeptOwner.serializer()), listOf(KeptOwner(owner, "Legacy"))))
        File(legacy, "audio/ab/track").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(5)) }
        f.phone("synthetic-A", "a-1")
        val a = f.native("synthetic-A")
        val blocker = File(f.files().root, "audio").apply { writeText("synthetic-conflict") }
        f.downloads.claimLegacy(a)
        assertTrue(f.downloads.store.hasLegacy())
        f.phone("synthetic-B", "b-1")
        val b = f.native("synthetic-B")
        assertFalse(f.downloads.store.hasLegacy())
        try { f.downloads.store.claimLegacy(b); fail("Interrupted claim reassigned") } catch (_: IllegalStateException) { }
        f.phone("synthetic-A", "a-2")
        val again = f.native("synthetic-A")
        assertTrue(blocker.delete())
        f.downloads.claimLegacy(again)
        assertFalse(f.downloads.store.hasLegacy())
        assertArrayEquals(byteArrayOf(5), File(f.files().root, "audio/ab/track").readBytes())
    }
}

private typealias WatchDownloadsStatus = dev.pampa.fluidify.wear.protocol.WatchDownloads
