package com.far.mqttcall.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.far.mqttcall.CallUiState
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
            .assertHeightIsEqualTo(190.dp)
        composeTestRule.onAllNodes(hasScrollAction()).assertCountEquals(0)
        composeTestRule.onNodeWithText("Channel: 3344").assertIsDisplayed()
        composeTestRule.onNodeWithText("Key: slot 1").assertIsDisplayed()
    }

    @Test
    fun management_entry_opens_separate_page_and_back_returns_to_call() {
        composeTestRule.setContent {
            MqttCallTheme {
                CallScreen(CallUiState(), {}, { false }, {}, false, {})
            }
        }
        composeTestRule.onNodeWithText("Manage brokers, channels & keys").performClick()
        composeTestRule.onNodeWithText("Brokers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithContentDescription("Push to talk").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("Exit").assertIsDisplayed().performClick()
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
}
