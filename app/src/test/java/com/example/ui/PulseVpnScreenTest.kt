package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.model.VpnConnectionState
import com.example.ads.AdMessage
import com.example.ads.AdState
import com.example.ui.theme.PulseVpnTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PulseVpnScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `unavailable ad notice is visible while connecting and is consumed`() {
        var adState by mutableStateOf(AdState(message = AdMessage.UNAVAILABLE))
        compose.setContent {
            PulseVpnTheme {
                PulseVpnScreen(
                    uiState = VpnUiState(connectionState = VpnConnectionState.Connecting),
                    adState = adState,
                    onToggleConnection = {},
                    onSelectServer = {},
                    onSyncRemote = {},
                    onDismissError = {},
                    onConsumeSnackbar = {},
                    onConsumeAdUnavailable = { adState = adState.copy(message = null) },
                )
            }
        }
        compose.onNodeWithText("Ad unavailable. Continuing to VPN connection.").assertExists()
        compose.mainClock.advanceTimeBy(10_000)
        compose.runOnIdle { org.junit.Assert.assertEquals(null, adState.message) }
    }

    @Test
    fun `users can select a server and connect without exposing configuration`() {
        val diagnostic = "Could not connect to hidden.example:443/ws via vmess://private"
        var state by mutableStateOf(
            VpnUiState(
                connectionState = VpnConnectionState.Error(diagnostic),
                servers = listOf(ServerChoice("first", "London"), ServerChoice("second", "Paris")),
                selectedServerId = "first",
            ),
        )
        var connectedServer: String? = null
        var syncRequested = false

        compose.setContent {
            PulseVpnTheme {
                PulseVpnScreen(
                    uiState = state,
                    onToggleConnection = {
                        if (state.connectionState is VpnConnectionState.Connected) {
                            state = state.copy(connectionState = VpnConnectionState.Disconnected)
                        } else {
                            connectedServer = state.selectedServerId
                            state = state.copy(connectionState = VpnConnectionState.Connected)
                        }
                    },
                    onSelectServer = { state = state.copy(selectedServerId = it) },
                    onSyncRemote = { syncRequested = true },
                    onDismissError = {},
                    onConsumeSnackbar = {},
                )
            }
        }

        compose.onNodeWithText("Could not connect to the VPN. Please try again.").assertExists()
        compose.onNodeWithText(diagnostic, substring = true).assertDoesNotExist()
        listOf("Edit", "Active URI", "Copy URI", "WS Path / SNI", "Local Bypass").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithTag("toggle_logs_button").assertDoesNotExist()
        compose.onNodeWithTag("server_item_1").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("sync_remote_button").performScrollTo().performClick()
        compose.onNodeWithTag("power_shield_button").performScrollTo().performClick()
        compose.onNodeWithText("Disconnect").assertExists()
        compose.onNodeWithTag("power_shield_button").performClick()
        compose.onNodeWithText("Connect").assertExists()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("second", connectedServer)
            org.junit.Assert.assertTrue(syncRequested)
        }
    }
    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `bottom banner reserves space below the connection control`() {
        compose.setContent {
            PulseVpnTheme {
                PulseVpnScreen(
                    uiState = VpnUiState(),
                    onToggleConnection = {},
                    onSelectServer = {},
                    onSyncRemote = {},
                    onDismissError = {},
                    onConsumeSnackbar = {},
                    banner = { Box(Modifier.fillMaxWidth().height(50.dp).testTag("test_banner")) },
                )
            }
        }
        listOf("power_shield_button", "sync_remote_button").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo()
            val control = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val banner = compose.onNodeWithTag("test_banner").getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("$tag overlaps banner", control.bottom <= banner.top)
        }
    }

}
