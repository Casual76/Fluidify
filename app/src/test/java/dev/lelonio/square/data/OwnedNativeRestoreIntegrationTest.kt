// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.playback.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual LibrespotPlayer.restore -> deferred handOver -> pushQueue command -> native event parser.
 * Only JNI dispatch is replaced: no Android native library/network/device is invoked by the fixture.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = OwnedNativeRestoreIntegrationTest.Host::class, sdk = [35])
class OwnedNativeRestoreIntegrationTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }
    private val track = CatalogTrack("spotify:track:one", "One", "Artist", durationMs = 60_000)
    private data class Load(val nonce: String?, val index: Int, val position: Int)
    @Test fun ownedEarlyRestoreHandoverResumesOneFortySecondOccurrence() = exercise("owned")
    @Test fun ownedImmediateRestoreAlsoUsesExactCommandAcknowledgement() = exercise("immediate")
    @Test fun competingSameUriRequestCannotStealOwnedNonceOrCheckpoint() = exercise("competing")
    @Test fun unrelatedSameUriTaggedWithAnotherNonceRemainsSeparate() = exercise("foreign")
    @Test fun remoteInterleavingRevokesPendingOwnedRestore() = exercise("remote")
    @Test fun transientRemoteBeforeAcknowledgementRevokesPendingOwnedRestore() = exercise("transient-remote")
    @Test fun recordingRevocationRejectsLateOwnedAcknowledgement() = exercise("revoke")
    @Test fun logoutReloginRejectsLateOwnedAcknowledgement() = exercise("account")

    private fun exercise(mode: String) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val history = ListeningRepository(context, db).apply { connect(HistoryAccount("owned")) }
        val players = mutableListOf<LibrespotPlayer>()
        fun producer(loads: MutableList<Load>) = LibrespotPlayer(context, Looper.getMainLooper(), PlayQueue(),
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
            onPlaybackActive = {}, onDuckReverb = {},
            loadQueueCommand = { uris, index, _, position, _, _, nonce ->
                assertEquals(listOf(track.uri), uris); loads += Load(nonce, index, position)
            }, refreshRemoteState = {}).also { it.shutdownEngineOnRelease = false; players += it }
        fun emit(p: LibrespotPlayer, kind: String, uri: String = track.uri, position: Long = 0) {
            p.onEvent(kind, uri, position); shadowOf(Looper.getMainLooper()).idle()
        }
        fun restore(p: LibrespotPlayer, saved: SavedPlayback, repository: ListeningRepository, now: Boolean) {
            val (token, valid) = saved.listeningRestoreAuthorization(repository)
            p.restore(listOf(PlayQueue.Track(track.uri, track.name, track.artist, durationMs = 60_000,
                artworkUri = null)), null, 0, saved.positionMs, Player.REPEAT_MODE_OFF,
                null, false, "", handOverNow = now, listeningOccurrenceId = token,
                listeningContinuityValid = valid)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val firstLoads = mutableListOf<Load>()
        val first = producer(firstLoads)
        restore(first, SavedPlayback(listOf(track), index = 0, positionMs = 0), history, true)
        assertNull(firstLoads.single().nonce)
        emit(first, "playback_request", "old-pump:1:1")
        emit(first, "playing")
        var wallOffset = 1000L
        var recorder = ListeningRecorder(history, this, { first }, { currentTime + wallOffset },
            { currentTime }, { it.isRemotePlayback() }, { (it as LibrespotPlayer).listeningOccurrence })
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        val handoff = recorder.playbackHandoff()!!
        val saved = SavedPlayback(listOf(track), index = 0, positionMs = 20_000,
            listeningOccurrenceId = handoff.first, historyContinuity = handoff.second)
        val store = PlaybackStore(context); store.save(saved)
        recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
        // New producer/repository and the real saved-queue authorization after process downtime.
        advanceTimeBy(3_600_000)
        val after = ListeningRepository(context, db).apply { restoreAccount() }
        val loaded = store.load()!!
        val loads = mutableListOf<Load>()
        val p = producer(loads)
        recorder = ListeningRecorder(after, this, { p }, { currentTime + wallOffset },
            { currentTime }, { it.isRemotePlayback() }, { (it as LibrespotPlayer).listeningOccurrence })
        try {
            restore(p, loaded, after, mode == "immediate")
            if (mode != "immediate") { assertTrue(loads.isEmpty()); p.handOverToEngine(); shadowOf(Looper.getMainLooper()).idle() }
            val nonce = loads.single().nonce!!
            assertEquals(20_000, loads.single().position)
            assertNull(p.listeningOccurrence.id) // paused queue alone proves no native ownership
            runCurrent()
            if (mode == "competing") {
                emit(p, "playback_request", "new-pump:1:7"); emit(p, "playing")
                assertNotEquals(handoff.first, p.listeningOccurrence.id)
                runCurrent(); advanceTimeBy(20_000); runCurrent()
            }
            if (mode == "revoke") { after.setRecording(false); after.setRecording(true) }
            if (mode == "account") { after.disconnect(); after.connect(HistoryAccount("owned")) }
            if (mode in listOf("remote", "transient-remote")) p.showRemote(RemotePlayback("remote", "Remote", track.uri,
                "One", "Artist", "", "", "", 0, 60_000, true, false, false, false))
            // No native event occurs during this transient remote boundary.
            if (mode == "transient-remote") p.showRemote(null)
            val body = buildJsonObject { put("nonce", if (mode == "foreign") "other-nonce" else nonce); put("request", "new-pump:1:9") }
            emit(p, "playback_restore_bound", body.toString())
            if (mode == "remote") p.showRemote(null)
            emit(p, "playback_request", "new-pump:1:9")
            emit(p, "paused", position = 20_000)
            runCurrent(); recorder.awaitRestore(); runCurrent()
            val owned = mode in listOf("owned", "immediate", "competing")
            if (owned) assertEquals(handoff.first, p.listeningOccurrence.id)
            emit(p, "playing", position = 20_000)
            runCurrent(); advanceTimeBy(20_000); runCurrent()
            // Replayed acknowledgements/identity notifications cannot rotate or reattach a token.
            val finalId = p.listeningOccurrence.id
            emit(p, "playback_restore_bound", body.toString()); emit(p, "playback_request", "new-pump:1:9")
            assertEquals(finalId, p.listeningOccurrence.id)
            recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
            val rows = after.records(after.session.value.access!!, 0, currentTime + 10_000).records
            val original = rows.single { it.id == "local:${handoff.first}" }
            assertEquals(if (owned) 40_000L else 20_000L, original.listenedMs)
            assertEquals(owned, original.qualified)
            assertEquals(if (mode in listOf("owned", "immediate")) 1 else 2, rows.size)
            assertTrue(rows.filter { it.id != original.id }.all { it.listenedMs == 20_000L && !it.qualified })
            assertEquals(0L, after.aggregate(after.session.value.access!!, 21_001, 3_620_000).measuredLocalMs)
        } finally {
            recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
            players.forEach { it.release() }; shadowOf(Looper.getMainLooper()).idle(); db.close()
        }
    }
    private fun Player.isRemotePlayback() = (this as LibrespotPlayer).isRemotePlayback
}
