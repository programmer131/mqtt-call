package com.far.mqttcall.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.far.mqttcall.CallUiState
import com.far.mqttcall.domain.AppDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallScreenTest {
    @Test
    fun defaults_and_security_status_contract_is_stable() {
        val state = CallUiState()

        assertEquals(AppDefaults.defaultBroker, state.broker)
        assertEquals(AppDefaults.defaultChannel, state.channel)
        assertEquals("call/channel/3344", state.topic)
        assertTrue(state.securityLevel == null)
    }
}
