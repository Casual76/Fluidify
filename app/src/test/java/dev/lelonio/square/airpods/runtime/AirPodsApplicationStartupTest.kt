package dev.lelonio.square.airpods.runtime

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.airpods.AacpStage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
class AirPodsApplicationStartupTest {
    @Before fun validProcessIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41001) }
    @Test fun backgroundProcessInstallationDoesNotGrantPermissionsCreateMediaOrStartService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        val created = AtomicInteger()
        val controller = AirPodsApplicationStartup.install(app, MutableStateFlow(false)) {
            created.incrementAndGet()
            error("Media must only be created after session admission")
        }.getOrThrow()
        assertEquals(0, created.get())
        assertEquals(AacpStage.CLOSED, controller.state.value.stage)
        assertFalse(controller.runtime.value.active)
        // Product availability grants no consent, permission, verification or active session.
        assertTrue(controller.runtime.value.productConformance)
        assertFalse(controller.runtime.value.setupVerified)
        assertFalse(controller.store.snapshot().getOrThrow().start.enabled)
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT))
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertNull(shadowOf(app).nextStartedService)
    }
}
