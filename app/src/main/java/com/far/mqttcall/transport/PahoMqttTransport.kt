package com.far.mqttcall.transport

import com.far.mqttcall.domain.BrokerProfile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class PahoMqttTransport : MqttTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<MqttEvent>(extraBufferCapacity = 64)

    private var client: MqttAsyncClient? = null
    private var activeTopic: String? = null
    private var activeProfile: BrokerProfile? = null
    private var reconnectJob: Job? = null
    private var shouldReconnect = false

    override val events = _events.asSharedFlow()

    override suspend fun connect(profile: BrokerProfile, topic: String) {
        withContext(Dispatchers.IO) {
            shouldReconnect = true
            reconnectJob?.cancel()
            reconnectJob = null
            disconnectClient()
            activeProfile = profile
            activeTopic = topic
            connectOnce(profile, topic)
        }
    }

    override suspend fun publish(payload: ByteArray, qos: Int) {
        withContext(Dispatchers.IO) {
            val mqttClient = client ?: error("MQTT client is not connected")
            val topic = activeTopic ?: error("MQTT topic is not selected")
            val message = MqttMessage(payload).apply {
                this.qos = qos
                isRetained = false
            }
            mqttClient.publish(topic, message).waitForCompletion()
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            shouldReconnect = false
            reconnectJob?.cancel()
            reconnectJob = null
            disconnectClient()
            _events.emit(MqttEvent.Disconnected)
        }
    }

    private fun connectOnce(profile: BrokerProfile, topic: String) {
        val scheme = if (profile.tls) "ssl" else "tcp"
        val uri = "$scheme://${profile.host}:${profile.port}"
        val mqttClient = MqttAsyncClient(
            uri,
            "mqtt-call-${UUID.randomUUID()}",
            MemoryPersistence(),
        )
        mqttClient.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                client = null
                _events.tryEmit(MqttEvent.Disconnected)
                if (shouldReconnect) scheduleReconnect()
            }

            override fun messageArrived(messageTopic: String, message: MqttMessage) {
                if (messageTopic == activeTopic) {
                    _events.tryEmit(MqttEvent.Message(message.payload.copyOf()))
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            keepAliveInterval = 30
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            isAutomaticReconnect = false
            profile.username?.let { setUserName(it) }
            profile.password?.let { setPassword(it.toCharArray()) }
        }

        try {
            mqttClient.connect(options).waitForCompletion()
            mqttClient.subscribe(topic, 0).waitForCompletion()
            client = mqttClient
            _events.tryEmit(MqttEvent.Connected)
        } catch (error: Exception) {
            runCatching { mqttClient.close() }
            _events.tryEmit(MqttEvent.Error(error.message ?: "MQTT connection failed"))
            if (shouldReconnect) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val profile = activeProfile ?: return
        val topic = activeTopic ?: return
        reconnectJob = scope.launch {
            var waitMs = 1_000L
            repeat(5) {
                delay(waitMs)
                if (!shouldReconnect) return@launch
                connectOnce(profile, topic)
                if (client != null) return@launch
                waitMs = (waitMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun disconnectClient() {
        val mqttClient = client ?: return
        runCatching {
            if (mqttClient.isConnected) mqttClient.disconnect().waitForCompletion()
            mqttClient.close()
        }
        client = null
    }
}
