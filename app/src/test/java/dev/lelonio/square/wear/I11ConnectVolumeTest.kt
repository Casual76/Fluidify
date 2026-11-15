package dev.lelonio.square.wear

import dev.lelonio.square.data.RemoteDevice
import dev.pampa.fluidify.wear.protocol.AckErrors
import dev.pampa.fluidify.wear.protocol.Command
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class I11ConnectVolumeTest {
    private val speaker = RemoteDevice("speaker", "Synthetic", "Speaker", 100, true, false)

    @Test fun refusedSpeakerCommandHasANegativeAckOutcomeForThatCommand() = runTest {
        val command = Command.SetVolume(.8f, "speaker")
        var destination: String? = null
        var phoneWrites = 0
        val error = i11WearCommandResult(command, 100) {
            val applied = i11ConnectVolume(command.level, command.deviceId, speaker, true, { false },
                remote = { id, _ -> destination = id; false }, phone = { phoneWrites++; true })
            if (applied) null else AckErrors.INTERNAL
        }
        assertEquals(AckErrors.INTERNAL, error)
        assertEquals("speaker", destination)
        assertEquals(0, phoneWrites)
    }

    @Test fun explicitPhoneDoesNotFallThroughToTheActiveSpeaker() = runTest {
        var phoneLevel = -1f
        assertTrue(i11ConnectVolume(.4f, "phone", speaker, true, { false },
            remote = { _, _ -> error("wrong destination") }, phone = { phoneLevel = it; true }))
        assertEquals(.4f, phoneLevel, 0f)
    }

    @Test fun explicitNativePhoneIdAlsoUsesThePhoneStream() = runTest {
        var phoneWrites = 0
        assertTrue(i11ConnectVolume(.5f, "native-phone-id", speaker, true, { it == "native-phone-id" },
            remote = { _, _ -> error("wrong destination") }, phone = { phoneWrites++; true }))
        assertEquals(1, phoneWrites)
    }

    @Test fun implicitVolumeUsesTheActiveConnectDeviceAndPreservesItsFailure() = runTest {
        var target: String? = null
        assertFalse(i11ConnectVolume(.5f, null, speaker, true, { false },
            remote = { id, raw -> target = id; assertEquals(32768, raw); false },
            phone = { error("wrong destination") }))
        assertEquals("speaker", target)
    }

    @Test fun invalidLevelCannotBecomeAnAppliedPhoneOrRemoteVolume() = runTest {
        assertFalse(i11ConnectVolume(Float.NaN, null, speaker, true, { false },
            remote = { _, _ -> error("invalid dispatch") }, phone = { error("invalid dispatch") }))
    }

    @Test fun unavailablePhoneAudioIsAlsoARefusal() = runTest {
        assertFalse(i11ConnectVolume(.5f, "phone", speaker, true, { false },
            remote = { _, _ -> error("wrong destination") }, phone = { false }))
    }
}
