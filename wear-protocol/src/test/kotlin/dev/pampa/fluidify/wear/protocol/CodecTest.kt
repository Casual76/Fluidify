package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {

    @Test
    fun snapshotRoundTrips() {
        val snapshot = PlaybackSnapshot(
            seq = 42,
            sentAtEpochMs = 1_000,
            source = PlaybackSource.PHONE,
            track = TrackInfo(
                uri = "spotify:track:abc",
                title = "Titolo",
                artist = "Artista",
                durationMs = 180_000,
                artKey = "ab67616d0000b273",
            ),
            positionMs = 12_000,
            sampledAtEpochMs = 990,
            isPlaying = true,
            shuffle = true,
            repeat = RepeatMode.ONE,
            liked = true,
            device = DeviceInfo("d1", "Pixel", DeviceKind.PHONE, isThisPhone = true, volume = 0.4f),
        )
        val bytes = WearCodec.encode(PlaybackSnapshot.serializer(), snapshot)
        assertEquals(snapshot, WearCodec.decode(PlaybackSnapshot.serializer(), bytes))
    }

    @Test
    fun defaultsAreNotWritten() {
        val text = WearCodec.encode(PlaybackSnapshot.serializer(), PlaybackSnapshot(seq = 1, sentAtEpochMs = 2))
            .decodeToString()
        assertEquals("""{"seq":1,"sentAtEpochMs":2}""", text)
    }

    @Test
    fun unknownFieldsFromANewerPeerAreIgnored() {
        val text = """{"seq":7,"sentAtEpochMs":3,"somethingNew":{"a":1},"isPlaying":true}"""
        val decoded = WearCodec.decode(PlaybackSnapshot.serializer(), text.encodeToByteArray())
        assertEquals(7, decoded.seq)
        assertTrue(decoded.isPlaying)
    }

    @Test
    fun unknownEnumValueFallsBackToDefault() {
        val text = """{"seq":1,"sentAtEpochMs":1,"repeat":"SHUFFLE_ALL_THE_THINGS"}"""
        assertEquals(RepeatMode.OFF, WearCodec.decode(PlaybackSnapshot.serializer(), text.encodeToByteArray()).repeat)
    }

    @Test
    fun commandsRoundTripThroughTheEnvelope() {
        val commands = listOf(
            Command.Play, Command.Pause, Command.TogglePlay, Command.Next, Command.Previous,
            Command.SeekTo(5_000), Command.SetShuffle(true), Command.SetRepeat(RepeatMode.ALL),
            Command.SetLiked("spotify:track:x", true), Command.SetVolume(0.5f, "dev"),
            Command.PlayContext("spotify:playlist:p", "spotify:track:t", shuffle = true, label = "Mix"),
            Command.PlayQueueIndex(3, "spotify:track:q"), Command.AddToQueue("spotify:track:y"),
            Command.StartRadio("spotify:track:z"), Command.Transfer("pc"),
            Command.SleepTimer(minutes = 15), Command.SleepTimer(atTrackEnd = true), Command.SleepTimer(cancel = true),
        )
        commands.forEachIndexed { index, command ->
            val envelope = CommandEnvelope(index.toLong(), command)
            val bytes = WearCodec.encode(CommandEnvelope.serializer(), envelope)
            assertEquals(envelope, WearCodec.decode(CommandEnvelope.serializer(), bytes))
        }
    }

    @Test
    fun commandDiscriminatorIsShort() {
        val text = WearCodec.encode(CommandEnvelope.serializer(), CommandEnvelope(1, Command.Next)).decodeToString()
        assertEquals("""{"id":1,"command":{"t":"next"}}""", text)
    }

    @Test
    fun decodeOrNullSwallowsGarbage() {
        assertNull(WearCodec.decodeOrNull(PlaybackSnapshot.serializer(), "not json".encodeToByteArray()))
    }

    @Test
    fun gzipRoundTrips() {
        val text = "Fluidify ".repeat(500).encodeToByteArray()
        val packed = WearCodec.gzip(text)
        assertTrue(packed.size < text.size / 10)
        assertEquals(text.decodeToString(), WearCodec.gunzip(packed).decodeToString())
    }

    @Test
    fun artKeyUsesTheSpotifyImageId() {
        assertEquals("ab67616d0000b273deadbeef", artKeyOf("https://i.scdn.co/image/ab67616d0000b273deadbeef"))
        assertEquals("ab67", artKeyOf("https://i.scdn.co/image/ab67?size=640"))
        assertNull(artKeyOf(null))
        assertNull(artKeyOf(" "))
    }

    @Test
    fun artKeyFallsBackToAStableHash() {
        val url = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
        val key = artKeyOf(url)!!
        assertTrue(key.startsWith("u"))
        assertEquals(key, artKeyOf(url))
        assertTrue(key != artKeyOf("$url?x"))
    }
}
