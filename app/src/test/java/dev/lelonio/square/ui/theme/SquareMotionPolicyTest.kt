package dev.lelonio.square.ui.theme

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SquareMotionPolicyTest {
    private val systemScale = object : MotionDurationScale {
        override var scaleFactor: Float = 1f
    }

    @get:Rule
    val compose = createComposeRule(effectContext = systemScale)

    @Test
    fun systemZeroReachesFluidConsumersThroughTheAppTheme() {
        val policy = policyUnderTheme(0f)
        assertEquals(0f, policy.durationScale, 0f)
        assertTrue(policy.reducedMotion)
        assertFalse(policy.allowDecorativeMotion)
        assertFalse(policy.allowElasticity)
    }

    @Test
    fun normalSystemMotionRemainsEnabled() {
        val policy = policyUnderTheme(1f)
        assertEquals(1f, policy.durationScale, 0f)
        assertFalse(policy.reducedMotion)
        assertTrue(policy.allowDecorativeMotion)
        assertTrue(policy.allowElasticity)
    }

    private fun policyUnderTheme(scale: Float): FluidMotionPolicy {
        systemScale.scaleFactor = scale
        var observed: FluidMotionPolicy? = null
        compose.setContent {
            SquareTheme(darkTheme = true, amoled = false, systemBars = false) {
                val policy = LocalFluidMotionPolicy.current
                SideEffect { observed = policy }
            }
        }
        compose.waitForIdle()
        return checkNotNull(observed)
    }
}
