package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthCodecTest {

    @Test
    fun requestAndGrantRoundTrip() {
        val request = AuthRequest(id = 7, reason = AuthReason.CREDENTIAL_REFUSED)
        assertEquals(request, WearCodec.decodeOrNull(AuthRequest.serializer(), WearCodec.encode(AuthRequest.serializer(), request)))

        val grant = AuthGrant(id = 7, ok = true, accessToken = "t", clientId = "c", username = "u", expiresAtEpochMs = 9)
        assertEquals(grant, WearCodec.decodeOrNull(AuthGrant.serializer(), WearCodec.encode(AuthGrant.serializer(), grant)))
    }

    @Test
    fun aRefusalCarriesNoToken() {
        val refused = AuthGrant(id = 1, ok = false, error = AuthErrors.SIGNED_OUT)
        val text = WearCodec.encode(AuthGrant.serializer(), refused).decodeToString()
        assertFalse(text.contains("accessToken"))
    }

    @Test
    fun accountStateRoundTrips() {
        val account = AccountState(signedIn = false, changedAtEpochMs = 3)
        assertEquals(account, WearCodec.decodeOrNull(AccountState.serializer(), WearCodec.encode(AccountState.serializer(), account)))
    }

    @Test
    fun aWatchSourceSnapshotDecodesOnAnOlderReader() {
        // A phone never receives one, but the enum value must not break decoding anywhere.
        val snapshot = PlaybackSnapshot(seq = 1, sentAtEpochMs = 1, source = PlaybackSource.WATCH)
        assertEquals(PlaybackSource.WATCH, WearCodec.decodeOrNull(PlaybackSnapshot.serializer(), WearCodec.encode(PlaybackSnapshot.serializer(), snapshot))?.source)
    }
}
