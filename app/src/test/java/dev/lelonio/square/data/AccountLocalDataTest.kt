package dev.lelonio.square.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared only: root owns compilation/execution. All identities, files and failures are synthetic. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountLocalDataTest {
    private val t = CatalogTrack("spotify:track:t", "T", "Artist", addedAt = "2024-01-01T00:00:00Z")
    private val u = CatalogTrack("spotify:track:u", "U", "Artist", addedAt = "2025-01-01T00:00:00Z")
    private val record = DownloadStore.FileRecord("aabb", "OGG_320", 42, 320, 123)

    @Test fun mountedObserversSwitchAndReturningAccountRecoversSearchAndDownloads() = runTest {
        val context = accountDataTestContext()
        val fixture = AccountLocalDataFixture()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        val search = SearchHistoryStore(context, fixture.accounts, StandardTestDispatcher(testScheduler))
        val seenOwners = mutableListOf<Map<String, List<String>>>()
        val seenSearch = mutableListOf<List<CatalogTrack>>()
        val seenStates = mutableListOf<Map<String, OwnerState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.owners.collect { seenOwners += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { search.tracks.collect { seenSearch += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.ownerStates.collect { seenStates += it } }
        val a = fixture.connect()
        store.setOwner(a, "playlist", listOf(t, t.copy(addedAt = u.addedAt)), DownloadStore.OwnerLabel("A"))
        search.record(a, t)
        fixture.logout()
        assertTrue(store.owners.value.isEmpty())
        assertTrue(search.tracks.value.isEmpty())
        assertTrue(seenOwners.last().isEmpty())
        assertTrue(seenSearch.last().isEmpty())
        assertTrue(seenStates.last().isEmpty())
        val b = fixture.connect("account-b")
        assertTrue(store.owners.value.isEmpty())
        assertTrue(search.tracks.value.isEmpty())
        store.addSingle(b, u)
        search.record(b, u)
        fixture.connect()
        assertEquals(listOf(t.addedAt, u.addedAt), store.tracksForOwner("playlist").map { it.addedAt })
        assertEquals(listOf(t), search.tracks.value)
        assertFalse(store.owners.value.containsKey(DownloadStore.SINGLES))
        assertEquals("A", store.labelOf("playlist")?.name)
    }

    @Test fun suspendedSearchAndDownloadWritesCannotEnterBOrReturningA() = runTest {
        val context = accountDataTestContext()
        val fixture = AccountLocalDataFixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val search = SearchHistoryStore(context, fixture.accounts, dispatcher)
        val store = DownloadStore(context, dispatcher, ::writeIndex, fixture.accounts)
        val a = fixture.connect()
        store.setOwner(a, "playlist", listOf(t), null)
        val refresh = store.beginOwnerRefresh(a, "playlist")!!
        val staleSearch = launch(start = CoroutineStart.UNDISPATCHED) { search.record(a, t) }
        val staleOwner = launch(start = CoroutineStart.UNDISPATCHED) { store.addSingle(a, u) }
        fixture.logout()
        val b = fixture.connect("account-b")
        store.addSingle(b, u)
        runCurrent()
        assertTrue(staleSearch.isCancelled)
        assertTrue(staleOwner.isCancelled)
        assertTrue(search.tracks.value.isEmpty())
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, store.refreshOwner(refresh, listOf(u), null))
        store.onProgress(a, t.uri, 0.9f)
        assertTrue(store.progress.value.isEmpty())
        try { store.onCompleted(a, t.uri, record); fail("Stale completion accepted") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        try { store.onFailed(a, t.uri, "late A failure"); fail("Stale failure accepted") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        fixture.connect()
        assertTrue(search.tracks.value.isEmpty())
        assertEquals(listOf(t), store.tracksForOwner("playlist"))
        assertTrue(store.files.value.isEmpty())
        assertTrue(store.failures.value.isEmpty())
    }

    @Test fun restoredHistoryAndCallerDisplayIdentityDoNotAuthorizeStorage() = runTest {
        val fixture = AccountLocalDataFixture()
        fixture.history = HistoryAccess(HistoryAccount("canonical-id"), 7)
        fixture.epoch = 1
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        store.load()
        assertNull(fixture.accounts.capture())
        fixture.kept = dev.lelonio.square.auth.LocalSpotifyAuthority.KeptIdentity("canonical-id", "synthetic")
        fixture.nativeId = "Display Name"
        assertFalse(fixture.accounts.verifyNativeAccount())
        assertTrue(store.owners.value.isEmpty())
        fixture.nativeId = "canonical-id"
        assertTrue(fixture.accounts.verifyNativeAccount())
        val old = fixture.accounts.capture()!!
        fixture.history = checkNotNull(old.history).copy(generation = 8)
        fixture.accounts.historyChanged()
        assertEquals(AccountLocalDataCoordinator.Origin.LOCAL, fixture.accounts.capture()?.origin)
        assertNull(fixture.accounts.captureOnline())
        assertFalse(fixture.accounts.isCurrent(old))
    }

    @Test fun suspendedNativeProofCannotReviveRevokedSession() = runTest {
        val answer = CompletableDeferred<String>()
        var history: HistoryAccess? = HistoryAccess(HistoryAccount("account-a"), 1)
        var epoch: Long? = 1
        val authority = dev.lelonio.square.auth.LocalSpotifyAuthority({ null }, {}, {
            dev.lelonio.square.auth.LocalSpotifyAuthority.KeptIdentity("account-a", "synthetic")
        })
        val accounts = AccountLocalDataCoordinator({ history }, { epoch }, { answer.await() }, authority, { true })
        val verification = async(start = CoroutineStart.UNDISPATCHED) { accounts.verifyNativeAccount() }
        accounts.revoke(); history = HistoryAccess(HistoryAccount("account-b"), 2); epoch = 2
        answer.complete("account-a")
        assertFalse(verification.await())
        assertNull(accounts.capture())
    }

    @Test fun sharedAudioSurvivesOtherAccountClearAndIsDeletedAfterLastReference() = runTest {
        val fixture = AccountLocalDataFixture()
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        val a = fixture.connect()
        store.setOwner(a, "A-one", listOf(t), null)
        store.setOwner(a, "A-two", listOf(t), null)
        store.onCompleted(a, t.uri, record)
        val audio = File(store.root, "audio/aa/bb").apply { parentFile!!.mkdirs(); writeText("audio") }
        val b = fixture.connect("account-b")
        store.addSingle(b, t) // explicit B request may reuse the physical file, never A's collection
        assertEquals(record, store.files.value[t.uri])
        fixture.connect()
        val deletes = mutableListOf<String>()
        store.clearAll(store.captureAccess()!!) { deletes += it; audio.delete() }
        assertTrue(deletes.isEmpty())
        assertEquals("audio", audio.readText())
        fixture.connect("account-b")
        store.clearAll(store.captureAccess()!!) { deletes += it; audio.delete() }
        assertEquals(listOf(t.uri), deletes)
        assertFalse(audio.exists())
        assertTrue(store.root.isDirectory)
    }

    @Test fun legacyFilesStayUnassignedAndProtectBytesDuringAnotherAccountPrune() = runTest {
        val context = accountDataTestContext()
        val root = legacy(context, version = 2)
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        store.load()
        assertTrue(store.legacy.value.pending)
        assertTrue(store.owners.value.isEmpty())
        assertTrue(store.files.value.isEmpty())
        val original = File(root, "index.json").readBytes()
        store.addSingle(a, t)
        store.onCompleted(a, t.uri, record)
        var deletes = 0
        store.clearAll(a) { deletes++ }
        assertEquals(0, deletes)
        assertArrayEquals(original, File(root, "index.json").readBytes())
        assertTrue(File(root, "audio/aa/bb").exists())
    }

    @Test fun migrationConfirmsTheDisplayedRecipientAndSurvivesProcessRestart() = runTest {
        val context = accountDataTestContext()
        legacy(context, version = 2)
        val fixture = AccountLocalDataFixture()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        val a = fixture.connect()
        val stale = store.beginLegacyAssignment(a)!!
        fixture.logout()
        val b = fixture.connect("account-b")
        try { store.confirmLegacyAssignment(stale); fail("Confirmed old recipient") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(store.legacy.value.pending)
        assertTrue(store.files.value.isEmpty())
        val choice = store.beginLegacyAssignment(b)!!
        assertEquals("account-b", choice.recipient)
        store.confirmLegacyAssignment(choice)
        val archived = store.owners.value.keys.single()
        assertEquals("spotify:playlist:legacy", store.originalLegacyOwner(archived))
        assertEquals(listOf(t.addedAt, u.addedAt, null), store.tracksForOwner(archived).map { it.addedAt })
        assertEquals(listOf(t.uri, t.uri, u.uri), store.owners.value[archived])
        assertTrue(store.pending(Long.MAX_VALUE).isEmpty()) // unfinished legacy u is not a new download consent
        assertNull(store.beginLegacyAssignment(b))
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        reopened.load()
        assertFalse(reopened.legacy.value.pending)
        assertEquals(store.owners.value, reopened.owners.value)
        fixture.connect()
        assertTrue(reopened.files.value.isEmpty())
        assertTrue(reopened.owners.value.isEmpty())
        assertNull(reopened.beginLegacyAssignment(reopened.captureAccess()!!))
    }

    @Test fun migrationKeepsNewCollectionsAndDoesNotResetTheirFailures() = runTest {
        val context = accountDataTestContext()
        legacy(context, version = 2)
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        store.setOwner(a, "spotify:playlist:legacy", listOf(u), DownloadStore.OwnerLabel("New"))
        store.onFailed(a, u.uri, "retry later")
        val failures = store.failures.value
        store.confirmLegacyAssignment(store.beginLegacyAssignment(a)!!)
        assertEquals(listOf(u), store.tracksForOwner("spotify:playlist:legacy"))
        assertEquals("New", store.labelOf("spotify:playlist:legacy")?.name)
        assertEquals(failures, store.failures.value)
        assertEquals(2, store.owners.value.size)
    }

    @Test fun interruptedMigrationBeforePublicationRemainsPendingAndRetryable() = runTest {
        val context = accountDataTestContext()
        val root = legacy(context, version = 2)
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        var reject = false
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), { file, text ->
            if (reject) writeDownloadIndexAtomically(file, text) { _, _ -> throw IOException("before rename") }
            else writeIndex(file, text)
        }, fixture.accounts)
        val choice = store.beginLegacyAssignment(a)!!
        val previous = File(root, "account-index-v1.json").readBytes()
        reject = true
        try { store.confirmLegacyAssignment(choice); fail("Expected injected write failure") }
        catch (_: IOException) { }
        assertTrue(store.legacy.value.pending)
        assertTrue(store.owners.value.isEmpty())
        assertArrayEquals(previous, File(root, "account-index-v1.json").readBytes())
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        reopened.load()
        assertTrue(reopened.legacy.value.pending)
        reopened.confirmLegacyAssignment(reopened.beginLegacyAssignment(a)!!)
        assertFalse(reopened.legacy.value.pending)
        assertEquals("audio", File(root, "audio/aa/bb").readText())
    }

    @Test fun interruptedMigrationAfterPublicationRecoversCommittedDestinationExactlyOnce() = runTest {
        val context = accountDataTestContext()
        legacy(context, version = 2)
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        var crash = false
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), { file, text ->
            writeIndex(file, text)
            if (crash) throw IOException("simulated process death after rename")
        }, fixture.accounts)
        val choice = store.beginLegacyAssignment(a)!!
        crash = true
        try { store.confirmLegacyAssignment(choice); fail("Expected process-death injection") }
        catch (_: IOException) { }
        // Discard the process object: disk, rather than its stale memory, is the recovery oracle.
        val restarted = AccountLocalDataFixture()
        val freshA = restarted.connect()
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, restarted.accounts)
        reopened.load()
        assertFalse(reopened.legacy.value.pending)
        assertEquals(1, reopened.owners.value.size)
        assertNull(reopened.beginLegacyAssignment(freshA))
        restarted.connect("account-b")
        assertTrue(reopened.owners.value.isEmpty())
        assertFalse(reopened.legacy.value.pending)
    }

    @Test fun corruptAccountDocumentNeverFallsBackToUnscopedLegacy() = runTest {
        val context = accountDataTestContext()
        val root = legacy(context, version = 1)
        File(root, "account-index-v1.json").writeText("{broken")
        val fixture = AccountLocalDataFixture()
        fixture.connect()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        try { store.load(); fail("Corrupt index was silently accepted") }
        catch (_: kotlinx.serialization.SerializationException) { }
        assertTrue(store.owners.value.isEmpty())
        assertTrue(store.files.value.isEmpty())
        assertEquals("{broken", File(root, "account-index-v1.json").readText())
        assertTrue(File(root, "audio/aa/bb").exists())
    }

    @Test fun legacyV1GlobalDatesStayUnknownAndV2OccurrenceDatesRemainDistinct() = runTest {
        val context = accountDataTestContext()
        legacy(context, version = 1)
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        store.confirmLegacyAssignment(store.beginLegacyAssignment(a)!!)
        val archived = store.owners.value.keys.single()
        assertEquals(listOf(null, null, null), store.tracksForOwner(archived).map { it.addedAt })
        assertNull(store.trackOf(t.uri)?.addedAt)
    }

    @Test fun likedConsentAndRetryStateBelongToTheirAccountsAndDoNotImportGlobalConsent() = runTest {
        val context = accountDataTestContext()
        context.getSharedPreferences("square_downloads", 0).edit().putBoolean("liked_songs", true).commit()
        val fixture = AccountLocalDataFixture()
        val settings = DownloadSettingsStore(context, fixture.accounts)
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        val a = fixture.connect()
        assertFalse(settings.downloadLikedSongs.value)
        settings.setDownloadLikedSongs(true)
        store.addSingle(a, t)
        repeat(DownloadStore.MAX_ATTEMPTS) { store.onFailed(a, t.uri, "unavailable") }
        val failures = store.failures.value
        assertEquals(listOf(t.uri), store.givenUp())
        val create = store.beginOwnerRefresh(a, DownloadStore.LIKED, true) { settings.downloadLikedSongs.value }!!
        settings.setDownloadLikedSongs(false)
        assertEquals(DownloadStore.OwnerRefreshResult.STALE, store.refreshOwner(create, listOf(u), null))
        settings.setDownloadLikedSongs(true)
        val b = fixture.connect("account-b")
        assertFalse(settings.downloadLikedSongs.value)
        assertTrue(store.failures.value.isEmpty())
        assertNull(store.beginOwnerRefresh(b, DownloadStore.LIKED, true) { settings.downloadLikedSongs.value })
        store.retryFailed(b)
        val freshA = fixture.connect()
        assertTrue(settings.downloadLikedSongs.value)
        assertEquals(failures, store.failures.value)
        store.retryFailed(freshA)
        assertEquals(listOf(t.uri), store.pending(Long.MAX_VALUE))
    }

    @Test fun pendingOtherAccountReferenceKeepsPhysicalLedgerUntilItsLastRemoval() = runTest {
        val fixture = AccountLocalDataFixture()
        val store = DownloadStore(accountDataTestContext(), StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        val a = fixture.connect()
        store.addSingle(a, t)
        val b = fixture.connect("account-b")
        store.addSingle(b, t) // no audio exists yet, so B owns a pending reference only
        val freshA = fixture.connect()
        store.onCompleted(freshA, t.uri, record)
        val audio = File(store.root, "audio/aa/bb").apply { parentFile!!.mkdirs(); writeText("audio") }
        val deleted = mutableListOf<String>()
        store.clearAll(freshA) { deleted += it; audio.delete() }
        assertTrue(deleted.isEmpty())
        val freshB = fixture.connect("account-b")
        assertTrue(store.files.value.isEmpty())
        store.clearAll(freshB) { deleted += it; audio.delete() }
        assertEquals(listOf(t.uri), deleted)
        assertFalse(audio.exists())
    }

    @Test fun deletionFailureRetainsItsPhysicalRecordForRestartAndRetry() = runTest {
        val context = accountDataTestContext()
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        val store = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        store.addSingle(a, t)
        store.onCompleted(a, t.uri, record)
        val audio = File(store.root, "audio/aa/bb").apply { parentFile!!.mkdirs(); writeText("audio") }
        try { store.clearAll(a) { throw IOException("unlink failed") }; fail("Expected deletion error") }
        catch (_: IOException) { }
        assertEquals("audio", audio.readText())
        val reopened = DownloadStore(context, StandardTestDispatcher(testScheduler), ::writeIndex, fixture.accounts)
        reopened.load()
        assertTrue(reopened.owners.value.isEmpty())
        assertEquals(record, reopened.files.value[t.uri])
        reopened.pruneOrphans(a) { audio.delete() }
        assertTrue(reopened.files.value.isEmpty())
        assertFalse(audio.exists())
    }

    @Test fun searchLegacyIsUnassignedAndAccountSearchRemovalSurvivesReopen() = runTest {
        val context = accountDataTestContext()
        context.getSharedPreferences("square_search_history", 0).edit().putString("tracks",
            """[{"uri":"spotify:track:legacy","name":"Legacy","artist":"Artist"}]""").commit()
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        val search = SearchHistoryStore(context, fixture.accounts, StandardTestDispatcher(testScheduler))
        assertTrue(search.tracks.value.isEmpty())
        search.record(a, t); search.record(a, u); search.record(a, t)
        assertEquals(listOf(t, u), search.tracks.value)
        search.remove(a, u.uri)
        val reopened = SearchHistoryStore(context, fixture.accounts, StandardTestDispatcher(testScheduler))
        assertEquals(listOf(t), reopened.tracks.value)
        val b = fixture.connect("account-b")
        reopened.record(b, u); reopened.clear(b)
        fixture.connect()
        assertEquals(listOf(t), reopened.tracks.value)
    }

    private fun legacy(context: android.content.Context, version: Int): File {
        val root = File(context.filesDir, DownloadStore.DIR_NAME).apply { mkdirs() }
        val occurrences = if (version == 2) ",\"ownerOccurrences\":{\"spotify:playlist:legacy\":[{\"uri\":\"${t.uri}\",\"addedAt\":\"${t.addedAt}\"},{\"uri\":\"${t.uri}\",\"addedAt\":\"${u.addedAt}\"},{\"uri\":\"${u.uri}\"}]}" else ""
        File(root, "index.json").writeText("""
            {"version":$version,"files":{"${t.uri}":{"trackId":"aabb","format":"OGG_320","bytes":42,"kbps":320,"downloadedAt":123}},
             "owners":{"spotify:playlist:legacy":["${t.uri}","${t.uri}","${u.uri}"]},
             "tracks":{"${t.uri}":{"uri":"${t.uri}","name":"T","artist":"Artist","addedAt":"${t.addedAt}"},
             "${u.uri}":{"uri":"${u.uri}","name":"U","artist":"Artist"}},
             "labels":{"spotify:playlist:legacy":{"name":"Legacy"}}$occurrences}
        """.trimIndent())
        File(root, "audio/aa/bb").apply { parentFile!!.mkdirs(); writeText("audio") }
        return root
    }

    private fun writeIndex(file: File, text: String) = writeDownloadIndexAtomically(file, text)
}
