package com.far.mqttcall.transport

import com.far.mqttcall.domain.BrokerProfile
import kotlinx.coroutines.flow.Flow

interface MqttTransport {
    val events: Flow<MqttEvent>

    suspend fun connect(profile: BrokerProfile, topic: String)

    suspend fun publish(payload: ByteArray, qos: Int)

    suspend fun disconnect()
}

sealed interface MqttEvent {
    data object Connected : MqttEvent
    data object Disconnected : MqttEvent
    data class Message(val payload: ByteArray) : MqttEvent
    data class Error(val message: String) : MqttEvent
}
