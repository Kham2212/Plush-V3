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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.model.MaintenanceConfig
import com.example.model.RoutingPreset
import com.example.model.RoutingRule
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
    fun `maintenance shows notice and disables connection while sync stays available`() {
        var state by mutableStateOf(
            VpnUiState(maintenance = MaintenanceConfig(true, "Scheduled work", "Back at noon.")),
        )
        compose.setContent {
            PulseVpnTheme {
                PulseVpnScreen(
                    uiState = state,
                    onToggleConnection = {},
                    onSelectServer = {},
                    onSyncRemote = {},
                    onDismissError = {},
                    onConsumeSnackbar = {},
                )
            }
        }
        compose.onNodeWithText("Scheduled work").assertExists()
        compose.onNodeWithText("Back at noon.").assertExists()
        compose.onNodeWithTag("power_shield_button").assertIsNotEnabled()
        compose.onNodeWithTag("sync_remote_button").assertIsEnabled()

        compose.runOnIdle { state = state.copy(maintenance = MaintenanceConfig(enabled = true)) }
        compose.onNodeWithText("VPN maintenance").assertExists()
        compose.onNodeWithText("VPN connections are temporarily unavailable.", substring = true).assertExists()

        compose.runOnIdle { state = state.copy(maintenance = MaintenanceConfig()) }
        compose.onNodeWithText("VPN maintenance").assertDoesNotExist()
        compose.onNodeWithTag("power_shield_button").assertIsEnabled()
    }

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
    fun `settings lets users add disable edit and delete a routing rule`() {
        var settings by mutableStateOf(false)
        var rules by mutableStateOf(emptyList<RoutingRule>())
        compose.setContent {
            PulseVpnTheme {
                if (settings) {
                    RoutingRulesSettings(
                        rules = rules,
                        onBack = { settings = false },
                        onSaveRule = { updated -> rules = listOf(updated) },
                        onDeleteRule = { id -> rules = rules.filterNot { it.id == id } },
                    )
                } else {
                    PulseVpnScreen(
                        uiState = VpnUiState(),
                        onToggleConnection = {},
                        onSelectServer = {},
                        onSyncRemote = {},
                        onDismissError = {},
                        onConsumeSnackbar = {},
                        onOpenSettings = { settings = true },
                    )
                }
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Routing Rules").assertExists()
        compose.onNodeWithText("Add rule").performClick()
        compose.onNodeWithText("Save rule").assertIsNotEnabled()
        compose.onNodeWithText("Action: Proxy").performClick()
        compose.onNodeWithText("Block").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("example.com")
        compose.onNodeWithText("Save rule").performClick()
        compose.onNodeWithText("Block · Domain").assertExists()
        compose.onNodeWithContentDescription("Enable rule for example.com").performClick().assertIsOff()

        compose.onNodeWithText("Edit rule").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("other.example")
        compose.onNodeWithText("Save rule").performClick()
        compose.onNodeWithContentDescription("Enable rule for other.example").assertIsOff()
        compose.onNodeWithText("Delete rule").performClick()
        compose.onNodeWithText("No custom rules yet.").assertExists()
    }

    @Test
    @Config(qualifiers = "w440dp-h1000dp")
    fun `settings shows presets off and lets users enable UDP blocking`() {
        var rules by mutableStateOf(RoutingPreset.entries.map { it.rule() })
        compose.setContent {
            PulseVpnTheme {
                RoutingRulesSettings(
                    rules = rules,
                    onBack = {},
                    onSaveRule = { rule -> rules = rules.map { if (it.id == rule.id) rule else it } },
                    onDeleteRule = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Enable rule for LAN / Private IP Bypass").assertIsOff()
        compose.onNodeWithContentDescription("Enable rule for Block UDP 443")
            .assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("UDP destination port 443").assertExists()
        compose.onNodeWithContentDescription("Enable rule for Proxy Google").assertIsOff()
        compose.onNodeWithContentDescription("Enable rule for Bypass CN / Private Domains & Public DNS").assertIsOff()
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
