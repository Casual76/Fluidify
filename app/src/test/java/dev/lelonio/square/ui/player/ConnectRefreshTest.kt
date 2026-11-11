package dev.lelonio.square.ui.player

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ConnectRefreshTest {
    @get:Rule val compose = createComposeRule()

    private fun failedRefreshKeepsClusterSelectable(message: String) {
        val speaker = MainViewModel.SpotifyDevice(
            id = "cluster-speaker", name = "Salotto", type = "Speaker",
            isActive = false, volume = -1, isThisPhone = false,
        )
        val state = mutableStateOf(MainViewModel.DevicesState(devices = listOf(speaker)))
        var selected: String? = null
        compose.setContent {
            MaterialTheme { DeviceList(state.value, onSelect = { selected = it }, onRefresh = {}) }
        }
        // No further cluster emission follows the fallback failure.
        compose.runOnIdle {
            state.value = runConnectRefreshFailure(state.value.copy(loading = true), message)
        }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Salotto").performClick()
        compose.runOnIdle {
            assertEquals("cluster-speaker", selected)
            assertEquals(listOf(speaker), state.value.devices)
            assertFalse(state.value.loading)
        }
    }

    @Test fun unavailableWebApiRetainsClusterSelection() =
        failedRefreshKeepsClusterSelectable("Configura la tua applicazione")

    @Test fun httpFailureRetainsClusterSelectionWithoutFreshCluster() =
        failedRefreshKeepsClusterSelectable("HTTP 503")

    @Test fun emptyListShowsTheFailureInsteadOfAnEmptySuccess() {
        val state = runConnectRefreshFailure(MainViewModel.DevicesState(loading = true), "HTTP 503")
        compose.setContent { MaterialTheme { DeviceList(state, {}, {}) } }
        compose.onNodeWithText("HTTP 503").assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.devices.isEmpty()); assertFalse(state.loading) }
    }
}
