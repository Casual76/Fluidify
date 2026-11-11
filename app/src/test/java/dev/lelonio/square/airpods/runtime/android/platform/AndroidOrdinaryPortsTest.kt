// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidOrdinaryPortsTest {
    private class LaunchContext : ContextWrapper(null) {
        var calls = 0
        var intent: Intent? = null
        var error: RuntimeException? = null
        override fun startActivity(intent: Intent) {
            calls++; error?.let { throw it }; this.intent = intent
        }
    }
    @Test fun nullContextCannotManufactureAcceptance() {
        assertEquals(PlatformResult.Denied(PlatformFailure.NO_CONTEXT), AndroidAssistantLaunchPort(null).requestVoiceCommand())
    }
    @Test fun constructionIsInertAndVoiceRequestIsOnlySubmission() {
        val c = LaunchContext(); val port = AndroidAssistantLaunchPort(c)
        assertEquals(0, c.calls)
        assertEquals(PlatformResult.RequestAccepted, port.requestVoiceCommand())
        assertEquals(Intent.ACTION_VOICE_COMMAND, c.intent?.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, c.intent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(1, c.calls)
    }
    @Test fun noHandlerAndSecurityAreTypedFailuresWithoutFallbackLaunch() {
        val c = LaunchContext(); val port = AndroidAssistantLaunchPort(c)
        c.error = ActivityNotFoundException()
        assertEquals(PlatformResult.Denied(PlatformFailure.NO_HANDLER), port.requestVoiceCommand())
        c.error = SecurityException()
        assertEquals(PlatformResult.Denied(PlatformFailure.SECURITY), port.requestVoiceCommand())
        assertEquals(2, c.calls)
    }
    @Test fun settingsFallbackIsIntentDataOnly() {
        val intent = manualBluetoothSettingsIntent()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, intent.action)
        assertEquals(0, intent.flags)
    }
}
