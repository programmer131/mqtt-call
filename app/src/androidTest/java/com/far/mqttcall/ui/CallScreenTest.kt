package com.far.mqttcall.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import com.far.mqttcall.CallUiState
import com.far.mqttcall.domain.AppDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    requestMicrophone = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Push to talk")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(190.dp)
    }
}
