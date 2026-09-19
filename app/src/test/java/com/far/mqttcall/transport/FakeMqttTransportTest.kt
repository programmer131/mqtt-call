package com.far.mqttcall.transport

import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.domain.channelTopic
import com.far.mqttcall.settings.InMemoryCallSettingsStore
import com.far.mqttcall.settings.SavedCallSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMqttTransportTest {
    @Test
    fun `fake transport emits connection messages and records topic and qos`() = runTest {
        val transport = RecordingMqttTransport()
        val topic = channelTopic("3344").getOrThrow()
        val profile = AppDefaults.defaultBroker

        transport.connect(profile, topic)
        transport.publish(byteArrayOf(1, 2), qos = 0)
        transport.emitMessage(byteArrayOf(3))

        assertEquals(topic, transport.connectedTopic)
        assertEquals(listOf(0), transport.publishedQos)
        assertEquals(MqttEvent.Connected, transport.events.first())
        assertEquals(byteArrayOf(3).toList(), transport.events.replayCache[1].let { event ->
            (event as MqttEvent.Message).payload.toList()
        })

        transport.disconnect()
        assertTrue(transport.events.replayCache.last() is MqttEvent.Disconnected)
    }

    @Test
    fun `settings load defaults and preserves custom broker channel and key`() {
        val store = InMemoryCallSettingsStore()
        assertEquals(
            SavedCallSettings(AppDefaults.defaultBroker, "3344", "PTT-DEMO-3344"),
            store.load(),
        )

        val custom = SavedCallSettings(
            broker = BrokerProfile("Custom", "localhost", 1883, false),
            channel = "99",
            key = "share-me",
        )
        store.save(custom)

        assertEquals(custom, store.load())
    }

    private class RecordingMqttTransport : MqttTransport {
        override val events = MutableSharedFlow<MqttEvent>(replay = 8)
        var connectedTopic: String? = null
        val publishedQos = mutableListOf<Int>()

        override suspend fun connect(profile: BrokerProfile, topic: String) {
            connectedTopic = topic
            events.emit(MqttEvent.Connected)
        }

        override suspend fun publish(payload: ByteArray, qos: Int) {
            publishedQos += qos
        }

        override suspend fun disconnect() {
            events.emit(MqttEvent.Disconnected)
        }

        suspend fun emitMessage(payload: ByteArray) {
            events.emit(MqttEvent.Message(payload))
        }
    }
}
