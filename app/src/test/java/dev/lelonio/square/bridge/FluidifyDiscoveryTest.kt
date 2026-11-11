package dev.lelonio.square.bridge

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.bridge.AiToolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowApplicationPackageManager

/** Exercises PackageManager discovery against the actual merged app manifest. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [FluidifyDiscoveryTest.ManifestDiscoveryPackageManager::class])
class FluidifyDiscoveryTest {
    /** Robolectric4.17's default queryContentProviders is an unconditional null stub.
     * Supply that platform query from the real merged-manifest ProviderInfo records;
     * metadata values and the client's discovery/filtering code are not fabricated.
     */
    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    class ManifestDiscoveryPackageManager : ShadowApplicationPackageManager() {
        @RealObject private lateinit var actual: PackageManager

        @Implementation
        public override fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo> =
            actual.getPackageInfo(RuntimeEnvironment.getApplication().packageName,
                PackageManager.GET_PROVIDERS or flags).providers.orEmpty().toList()
    }

    @Test fun packagedHostIsDiscoverableWithoutInitializingAccountsOrPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.ai.tools"
        val hosts = AiToolClient(context).discover()

        val packaged = context.packageManager.queryContentProviders(null, 0, PackageManager.GET_META_DATA)
            .orEmpty().joinToString { "${it.authority}:${it.metaData}" }
        assertTrue("Fluidify's packaged TOOL_HOST metadata must satisfy shared discovery; package=${context.packageName}; providers=$packaged; hosts=$hosts", hosts.any { it.authority == authority })
        assertEquals(context.packageName, hosts.single { it.authority == authority }.packageName)
    }
}
