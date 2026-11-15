package dev.lelonio.square.wear

import dev.lelonio.square.data.AccountLocalDataFixture
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WearCommandAccessTest {
    @Test fun preSessionWatchCanControlTheCurrentPlayer() = runTest {
        val fixture = AccountLocalDataFixture()
        val access = fixture.connect()
        // Actual envelope sent by the older watch: no accountSession field.
        val legacy = WearCodec.decodeOrNull(CommandEnvelope.serializer(),
            """{"id":17,"command":{"t":"toggle"}}""".toByteArray())!!
        assertNull("the former all-command gate rejected this watch", fixture.accounts.captureRequest(legacy.accountSession))
        assertSame(access, WearCommandAccess.capture(fixture.accounts, legacy))
    }

    @Test fun legacyTransportCannotGainAccountDataOrLibraryWrites() = runTest {
        val fixture = AccountLocalDataFixture()
        fixture.connect()
        val accountCommands = listOf(Command.SetLiked("spotify:track:a", true),
            Command.PlayContext("spotify:playlist:a"), Command.AddToQueue("spotify:track:a"),
            Command.SetPinned("spotify:playlist:a", true), Command.Transfer("old-device"),
            Command.SetVolume(.5f, "old-device"))
        for (command in accountCommands) {
            assertNull(WearCommandAccess.capture(fixture.accounts, CommandEnvelope(1, command)))
        }
    }

    @Test fun explicitOldSessionNeverFallsBackAfterAccountSwitchOrRestart() = runTest {
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        val request = CommandEnvelope(1, Command.Pause, a.requestSession)
        assertSame(a, WearCommandAccess.capture(fixture.accounts, request))
        val b = fixture.connect("account-b")
        assertNull(WearCommandAccess.capture(fixture.accounts, request))
        assertSame(b, WearCommandAccess.capture(fixture.accounts, request.copy(accountSession = b.requestSession)))
        fixture.connect("account-b")
        assertNull(WearCommandAccess.capture(fixture.accounts, request.copy(accountSession = b.requestSession)))
    }

    @Test fun signedOutPhoneNeverAdmitsLegacyTransport() = runTest {
        val fixture = AccountLocalDataFixture()
        fixture.connect()
        fixture.logout()
        assertNull(WearCommandAccess.capture(fixture.accounts, CommandEnvelope(1, Command.Play)))
    }
}
