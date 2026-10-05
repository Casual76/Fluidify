package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCodecTest {

    @Test
    fun offerRoundTrips() {
        val offer = UpdateOffer(versionName = "1.5.0", versionCode = 8, sizeBytes = 4_747_461, sha256 = "ab12", requestedByUser = true)
        assertEquals(offer, WearCodec.decode(UpdateOffer.serializer(), WearCodec.encode(UpdateOffer.serializer(), offer)))
    }

    @Test
    fun statusRoundTripsEveryPhase() {
        UpdatePhase.entries.forEach { phase ->
            val status = UpdateStatus(phase, "1.5.0", progress = 0.5f, reason = "x")
            assertEquals(status, WearCodec.decode(UpdateStatus.serializer(), WearCodec.encode(UpdateStatus.serializer(), status)))
        }
    }

    @Test
    fun updatePathsCarryNoProtocolVersion() {
        // A watch any number of majors behind must still hear the offer that brings it up to date.
        listOf(WearPaths.HELLO, WearPaths.UPDATE_OFFER, WearPaths.UPDATE_STATUS, WearPaths.UPDATE_APK, WearPaths.UPDATE_REQUEST).forEach { path ->
            assert(!path.contains("/${ProtocolVersion.MAJOR}/")) { path }
        }
    }

    @Test
    fun offerCarriesChannelAndChangelog() {
        val offer = UpdateOffer(versionName = "1.7.0", channel = UpdateChannels.BETA, changelog = "Novità\n- una\n- due")
        val decoded = WearCodec.decode(UpdateOffer.serializer(), WearCodec.encode(UpdateOffer.serializer(), offer))
        assertEquals(offer, decoded)
        assertEquals("beta", decoded.channel)
    }

    @Test
    fun offerFromBeforeChannelAndChangelogStillDecodes() {
        val old = """{"versionName":"1.6.4","sizeBytes":10,"sha256":"ab","requestedByUser":true}"""
        val offer = WearCodec.decode(UpdateOffer.serializer(), old.encodeToByteArray())
        assertEquals("stable", offer.channel)
        assertEquals("", offer.changelog)
        assertTrue(offer.requestedByUser)
    }

    @Test
    fun offerFromTheFutureWithUnknownFieldsStillDecodes() {
        val future = """{"versionName":"9.0.0","channel":"nightly","somethingNew":{"a":1}}"""
        val offer = WearCodec.decode(UpdateOffer.serializer(), future.encodeToByteArray())
        assertEquals("9.0.0", offer.versionName)
        // A line this build does not know is read as the stable one.
        assertEquals("stable", UpdateChannels.parse(offer.channel))
    }

    @Test
    fun defaultChannelAndChangelogAreNotWritten() {
        val json = WearCodec.encode(UpdateOffer.serializer(), UpdateOffer(versionName = "1.0.0")).decodeToString()
        assertTrue(json, !json.contains("channel") && !json.contains("changelog"))
    }

    @Test
    fun helloCarriesUpdateChannelAndOldHelloReadsAsStable() {
        val hello = Hello(Role.PHONE, "1.7.0", 12, updateChannel = UpdateChannels.BETA)
        assertEquals(hello, WearCodec.decode(Hello.serializer(), WearCodec.encode(Hello.serializer(), hello)))
        val old = """{"role":"PHONE","versionName":"1.6.4","versionCode":11}"""
        assertEquals("stable", WearCodec.decode(Hello.serializer(), old.encodeToByteArray()).updateChannel)
    }

    @Test
    fun channelParsingFallsBackToStable() {
        assertEquals("beta", UpdateChannels.parse("beta"))
        assertEquals("beta", UpdateChannels.parse("BETA"))
        assertEquals("stable", UpdateChannels.parse("stable"))
        assertEquals("stable", UpdateChannels.parse(""))
        assertEquals("stable", UpdateChannels.parse(null))
        assertEquals("stable", UpdateChannels.parse("canary"))
    }

    @Test
    fun changelogShorterThanTheLimitIsOnlyTrimmed() {
        assertEquals("a\nb", UpdateChangelog.truncate("  a\nb \n", 100))
        assertEquals("", UpdateChangelog.truncate("   ", 10))
    }

    @Test
    fun changelogLongerThanTheLimitIsCutWithAnEllipsisAtAWord() {
        val text = "uno due tre quattro cinque sei sette otto nove dieci"
        val cut = UpdateChangelog.truncate(text, 30)
        assertTrue(cut, cut.length <= 30)
        assertTrue(cut, cut.endsWith("…"))
        assertTrue(cut, text.startsWith(cut.dropLast(1)))
        assertTrue(cut, cut.dropLast(1).split(' ').all { it in text.split(' ') })
    }

    @Test
    fun changelogIsNeverCutInsideASurrogatePair() {
        val text = "ab" + "🎵".repeat(10)
        (3..12).forEach { limit ->
            val cut = UpdateChangelog.truncate(text, limit)
            assertTrue("$limit", cut.length <= limit)
            // What precedes the ellipsis never ends on a lone high surrogate.
            assertTrue("$limit", !Character.isHighSurrogate(cut.dropLast(1).lastOrNull() ?: 'a'))
        }
    }

    @Test
    fun checkReplyRoundTripsAndDecodesWithJustAResult() {
        val reply = UpdateCheckReply(UpdateCheckReply.UPDATE, "1.7.1")
        assertEquals(reply, WearCodec.decode(UpdateCheckReply.serializer(), WearCodec.encode(UpdateCheckReply.serializer(), reply)))
        val bare = WearCodec.decode(UpdateCheckReply.serializer(), """{"result":"up-to-date"}""".encodeToByteArray())
        assertEquals("", bare.versionName)
        assertEquals(UpdateCheckReply.UP_TO_DATE, bare.result)
    }

    @Test
    fun requestPathIsUnversionedAndDistinctFromTheRest() {
        assertEquals("/fluidify/update/request", WearPaths.UPDATE_REQUEST)
        assertEquals(4, setOf(WearPaths.UPDATE_OFFER, WearPaths.UPDATE_STATUS, WearPaths.UPDATE_APK, WearPaths.UPDATE_REQUEST).size)
    }

    @Test
    fun waitingForPlaybackIsAnAcceptWithAReason() {
        val status = UpdateStatus(UpdatePhase.ACCEPT, "1.7.0", reason = UpdateStatus.REASON_WAITING_PLAYBACK)
        val decoded = WearCodec.decode(UpdateStatus.serializer(), WearCodec.encode(UpdateStatus.serializer(), status))
        assertEquals(UpdatePhase.ACCEPT, decoded.phase)
        assertEquals("waiting-playback", decoded.reason)
    }
}
