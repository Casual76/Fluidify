package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the phone does with a watch that is a version ahead, or hostile: it answers instead of
 * leaving the watch to time out, and it refuses what would hurt the phone.
 */
class HardeningTest {

    private fun bytes(text: String) = text.encodeToByteArray()

    // --- A type the phone has never heard of ------------------------------------------

    @Test
    fun unknownCommandTypeIsNotDecodedButItsIdIsRecovered() {
        val payload = bytes("""{"id":41,"command":{"t":"teleport","where":"moon"}}""")
        assertNull(WearCodec.decodeOrNull(CommandEnvelope.serializer(), payload))
        assertEquals(41L, WearCodec.peekId(payload))
    }

    @Test
    fun unknownRpcMethodIsNotDecodedButItsIdIsRecovered() {
        val payload = bytes("""{"id":7,"method":{"t":"hologram","depth":3}}""")
        assertNull(WearCodec.decodeOrNull(RpcRequest.serializer(), payload))
        assertEquals(7L, WearCodec.peekId(payload))
    }

    @Test
    fun aKnownCommandStillDecodes() {
        val payload = WearCodec.encode(CommandEnvelope.serializer(), CommandEnvelope(5, Command.Next))
        assertEquals(CommandEnvelope(5, Command.Next), WearCodec.decodeOrNull(CommandEnvelope.serializer(), payload))
        assertEquals(5L, WearCodec.peekId(payload))
    }

    @Test
    fun peekIdGivesUpOnWhatHasNoNumericId() {
        assertNull(WearCodec.peekId(bytes("not json")))
        assertNull(WearCodec.peekId(bytes("[1,2,3]")))
        assertNull(WearCodec.peekId(bytes("""{"command":{"t":"x"}}""")))
        assertNull(WearCodec.peekId(bytes("""{"id":"12","command":{"t":"x"}}""")))
        assertNull(WearCodec.peekId(bytes("""{"id":1.5}""")))
        assertNull(WearCodec.peekId(bytes("""{"id":null}""")))
        assertNull(WearCodec.peekId(ByteArray(0)))
    }

    @Test
    fun theUnsupportedAckRoundTrips() {
        val ack = CommandAck(id = 41, ok = false, error = AckErrors.UNSUPPORTED, sentAtEpochMs = 9)
        val decoded = WearCodec.decode(CommandAck.serializer(), WearCodec.encode(CommandAck.serializer(), ack))
        assertEquals(ack, decoded)
        assertFalse(decoded.ok)
    }

    @Test
    fun theUnsupportedRpcAnswerRoundTrips() {
        val response = RpcResponse(id = 7, ok = false, error = AckErrors.UNSUPPORTED)
        assertEquals(response, WearCodec.decode(RpcResponse.serializer(), WearCodec.encode(RpcResponse.serializer(), response)))
    }

    // --- Decompression bombs -----------------------------------------------------------

    @Test
    fun gunzipRefusesAPayloadThatUnpacksPastTheCeiling() {
        val bomb = WearCodec.gzip(ByteArray(2 * 1024 * 1024))
        assertTrue("a couple of KB should stand for megabytes", bomb.size < 16 * 1024)
        try {
            WearCodec.gunzip(bomb, maxBytes = 1024 * 1024)
            fail("expected PayloadTooLargeException")
        } catch (expected: PayloadTooLargeException) {
            assertEquals(1024 * 1024, expected.limit)
        }
    }

    @Test
    fun gunzipAcceptsAPayloadExactlyAtTheCeiling() {
        val exact = ByteArray(64 * 1024) { (it % 251).toByte() }
        assertEquals(exact.size, WearCodec.gunzip(WearCodec.gzip(exact), maxBytes = exact.size).size)
    }

    @Test
    fun gunzipDefaultCeilingIsGenerousForRealAnswers() {
        val answer = "Fluidify ".repeat(100_000).encodeToByteArray()
        assertEquals(answer.size, WearCodec.gunzip(WearCodec.gzip(answer)).size)
    }

    // --- What a question may ask for ---------------------------------------------------

    @Test
    fun queueWindowIsClampedToTheAllowedRange() {
        assertEquals(RpcMethod.Queue(0, 0), RpcLimits.sanitised(RpcMethod.Queue(before = -5, after = -1)))
        assertEquals(
            RpcMethod.Queue(RpcLimits.MAX_QUEUE_SIDE, RpcLimits.MAX_QUEUE_SIDE),
            RpcLimits.sanitised(RpcMethod.Queue(before = 10_000, after = Int.MAX_VALUE)),
        )
        assertEquals(RpcMethod.Queue(3, 60), RpcLimits.sanitised(RpcMethod.Queue()))
    }

    @Test
    fun contextPagingIsClamped() {
        val negative = RpcLimits.sanitised(RpcMethod.Context("spotify:playlist:p", offset = -10, limit = Int.MIN_VALUE))
        assertEquals(RpcMethod.Context("spotify:playlist:p", offset = 0, limit = 0), negative)
        val huge = RpcLimits.sanitised(RpcMethod.Context("spotify:playlist:p", offset = 5, limit = 1_000_000))
        assertEquals(RpcMethod.Context("spotify:playlist:p", offset = 5, limit = RpcLimits.MAX_CONTEXT_LIMIT), huge)
        assertEquals(RpcMethod.Context("spotify:playlist:p"), RpcLimits.sanitised(RpcMethod.Context("spotify:playlist:p")))
    }

    @Test
    fun searchQueryIsCut() {
        val cut = RpcLimits.sanitised(RpcMethod.Search("x".repeat(5_000))) as RpcMethod.Search
        assertEquals(RpcLimits.MAX_QUERY_LENGTH, cut.query.length)
        assertEquals(RpcMethod.Search("daft punk"), RpcLimits.sanitised(RpcMethod.Search("daft punk")))
    }

    @Test
    fun listsOfUrisAreCapped() {
        val many = List(1_000) { "spotify:track:$it" }
        assertEquals(RpcLimits.MAX_URIS, (RpcLimits.sanitised(RpcMethod.Downloads(many)) as RpcMethod.Downloads).uris.size)
        assertEquals(RpcLimits.MAX_URIS, (RpcLimits.sanitised(RpcMethod.Liked(many)) as RpcMethod.Liked).uris.size)
    }

    @Test
    fun questionsWithNothingToClampPassThrough() {
        assertEquals(RpcMethod.Home, RpcLimits.sanitised(RpcMethod.Home))
        assertEquals(RpcMethod.Devices, RpcLimits.sanitised(RpcMethod.Devices))
        assertEquals(RpcMethod.Library(LibrarySection.ALBUMS), RpcLimits.sanitised(RpcMethod.Library(LibrarySection.ALBUMS)))
    }

    // --- Where a cover may come from ---------------------------------------------------

    @Test
    fun spotifyImageHostsAreAllowed() {
        assertTrue(ImageHosts.isAllowed("https://i.scdn.co/image/ab67616d0000b273deadbeef"))
        assertTrue(ImageHosts.isAllowed("https://mosaic.scdn.co/640/abc"))
        assertTrue(ImageHosts.isAllowed("https://misc.scdn.co/liked-songs/liked-songs-640.png"))
        assertTrue(ImageHosts.isAllowed("https://image-cdn-ak.spotifycdn.com/image/abc"))
        assertTrue(ImageHosts.isAllowed("https://I.SCDN.CO/image/abc"))
        assertTrue(ImageHosts.isAllowed("https://i.scdn.co:443/image/abc"))
    }

    @Test
    fun anythingElseIsRefused() {
        assertFalse(ImageHosts.isAllowed("http://i.scdn.co/image/abc"))
        assertFalse(ImageHosts.isAllowed("https://evil.example/image/abc"))
        assertFalse(ImageHosts.isAllowed("https://scdn.co.evil.example/image/abc"))
        assertFalse(ImageHosts.isAllowed("https://notscdn.co/image/abc"))
        assertFalse(ImageHosts.isAllowed("https://scdn.co/image/abc"))
        assertFalse(ImageHosts.isAllowed("https://i.scdn.co@evil.example/image/abc"))
        assertFalse(ImageHosts.isAllowed("https://i.scdn.co:8443/image/abc"))
        assertFalse(ImageHosts.isAllowed("https://192.168.1.1/admin"))
        assertFalse(ImageHosts.isAllowed("file:///data/data/dev.lelonio.square/x"))
        assertFalse(ImageHosts.isAllowed("content://media/external/images/1"))
        assertFalse(ImageHosts.isAllowed(""))
    }
}
