package com.far.mqttcall.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.far.mqttcall.CallUiState
import com.far.mqttcall.ConnectionState
import com.far.mqttcall.domain.AppDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun defaults_and_security_status_contract_is_stable() {
        val state = CallUiState()

        assertEquals(AppDefaults.defaultBroker, state.broker)
        assertEquals(AppDefaults.defaultChannel, state.channel)
        assertEquals("call/channel/3344", state.topic)
        assertTrue(state.securityLevel == null)
    }

    @Test
    fun push_to_talk_is_visible_without_scrolling() {
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(
                    state = CallUiState(),
                    onAction = {},
                    onPttPress = { false },
                    onExit = {},
                    showMicrophoneSettings = false,
                    openAppSettings = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Push to talk")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(280.dp)
        composeTestRule.onAllNodes(hasScrollAction()).assertCountEquals(0)
        composeTestRule.onNodeWithText("Channel: 3344").assertIsDisplayed()
        composeTestRule.onNodeWithText("Key: KEY 1").assertIsDisplayed()
    }

    @Test
    fun management_entry_opens_separate_page_and_back_returns_to_call() {
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(CallUiState(), {}, { false }, {}, false, {})
            }
        }
        composeTestRule.onNodeWithContentDescription("Manage brokers, channels & keys").performClick()
        composeTestRule.onNodeWithText("Brokers").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithContentDescription("Push to talk").assertIsDisplayed()
    }

    @Test
    fun copy_channel_id_action_is_visible_on_main_page() {
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(CallUiState(), {}, { false }, {}, false, {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Copy channel ID").assertIsDisplayed().performClick()
    }

    @Test
    fun exit_action_is_in_top_bar_and_invokes_callback() {
        var exits = 0
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(
                    state = CallUiState(),
                    onAction = {},
                    onPttPress = { false },
                    onExit = { exits++ },
                    showMicrophoneSettings = false,
                    openAppSettings = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Exit").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun denied_microphone_shows_action_to_open_app_settings() {
        var opens = 0
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(
                    state = CallUiState(),
                    onAction = {},
                    onPttPress = { false },
                    onExit = {},
                    showMicrophoneSettings = true,
                    openAppSettings = { opens++ },
                )
            }
        }

        composeTestRule.onNodeWithText("App Settings").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun connected_call_shows_status_and_current_speaker_area() {
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(
                    state = CallUiState(
                        connection = ConnectionState.CONNECTED,
                        talkerName = "AHMED",
                    ),
                    onAction = {},
                    onPttPress = { false },
                    onExit = {},
                    showMicrophoneSettings = false,
                    openAppSettings = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Buffer: buffering").assertIsDisplayed()
        composeTestRule.onNodeWithText("Current Speaker").assertIsDisplayed()
        composeTestRule.onNodeWithText("AHMED").assertIsDisplayed()
    }

    @Test
    fun manage_page_shows_user_profile_section() {
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(CallUiState(), {}, { false }, {}, false, {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Manage brokers, channels & keys").performClick()
        composeTestRule.onNodeWithText("User Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Encryption Keys").assertIsDisplayed()
    }
}
