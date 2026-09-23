package com.far.mqttcall.transport

import com.far.mqttcall.domain.BrokerProfile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

class PahoMqttTransport(
    private val clientFactory: (String, String) -> MqttAsyncClient = { uri, id ->
        MqttAsyncClient(uri, id, MemoryPersistence())
    },
) : MqttTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<MqttEvent>(extraBufferCapacity = 64)
    private val lifecycleLock = Any()
    private val liveClients = mutableSetOf<MqttAsyncClient>()
    @Volatile private var aborted = false

    @Volatile private var client: MqttAsyncClient? = null
    private var activeTopic: String? = null
    private var activeProfile: BrokerProfile? = null
    private var reconnectJob: Job? = null
    @Volatile private var shouldReconnect = false

    override val events = _events.asSharedFlow()

    override suspend fun connect(profile: BrokerProfile, topic: String) {
        withContext(Dispatchers.IO) {
            synchronized(lifecycleLock) {
                check(!aborted) { "MQTT transport was aborted" }
                shouldReconnect = true
            }
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
            check(!aborted) { "MQTT transport was aborted" }
            mqttClient.publish(topic, message).waitForCompletion(OPERATION_TIMEOUT_MS)
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

    /** Terminal, non-blocking shutdown, including a client still waiting for CONNACK. */
    fun abort() {
        val clients = synchronized(lifecycleLock) {
            if (aborted) return
            aborted = true
            shouldReconnect = false
            client = null
            liveClients.toList()
        }
        scope.cancel()
        // Paho's force-close can wait on its internal threads: never do it on the service thread.
        clients.forEach { mqttClient ->
            CoroutineScope(Dispatchers.IO).launch { forceClose(mqttClient) }
        }
    }

    private fun connectOnce(profile: BrokerProfile, topic: String) {
        if (aborted || !shouldReconnect) return
        val scheme = if (profile.tls) "ssl" else "tcp"
        val uri = "$scheme://${profile.host}:${profile.port}"
        val mqttClient = clientFactory(
            uri,
            "mqtt-call-${UUID.randomUUID()}",
        )
        mqttClient.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                synchronized(lifecycleLock) {
                    if (client === mqttClient) client = null
                }
                if (aborted) return
                _events.tryEmit(MqttEvent.Disconnected)
                scope.launch { forceClose(mqttClient) }
                if (shouldReconnect) scheduleReconnect()
            }

            override fun messageArrived(messageTopic: String, message: MqttMessage) {
                if (!aborted && messageTopic == activeTopic) {
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
            connectionTimeout = 10
            profile.username?.let { setUserName(it) }
            profile.password?.let { setPassword(it.toCharArray()) }
        }

        val registered = synchronized(lifecycleLock) {
            if (aborted || !shouldReconnect) false else {
                liveClients += mqttClient
                client = mqttClient // Register before connect so abort can close pending connections.
                true
            }
        }
        if (!registered) {
            forceClose(mqttClient)
            return
        }
        try {
            mqttClient.connect(options).waitForCompletion(CONNECT_TIMEOUT_MS)
            check(!aborted && shouldReconnect) { "MQTT connection was stopped" }
            mqttClient.subscribe(topic, 0).waitForCompletion(OPERATION_TIMEOUT_MS)
            synchronized(lifecycleLock) {
                check(!aborted && shouldReconnect && client === mqttClient) { "MQTT connection was stopped" }
                _events.tryEmit(MqttEvent.Connected)
            }
        } catch (error: Exception) {
            forceClose(mqttClient)
            if (!aborted && shouldReconnect) {
                _events.tryEmit(MqttEvent.Error(error.message ?: "MQTT connection failed"))
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (aborted || !shouldReconnect) return
        if (reconnectJob?.isActive == true) return
        val profile = activeProfile ?: return
        val topic = activeTopic ?: return
        reconnectJob = scope.launch {
            var waitMs = 1_000L
            repeat(5) {
                delay(waitMs)
                if (aborted || !shouldReconnect) return@launch
                connectOnce(profile, topic)
                if (client != null) return@launch
                waitMs = (waitMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun disconnectClient() {
        val mqttClient = synchronized(lifecycleLock) {
            client.also { client = null }
        } ?: return
        try {
            if (mqttClient.isConnected) {
                runCatching { mqttClient.disconnect(0).waitForCompletion(DISCONNECT_TIMEOUT_MS) }
            }
        } finally {
            forceClose(mqttClient)
        }
    }

    private fun forceClose(mqttClient: MqttAsyncClient) {
        runCatching { mqttClient.disconnectForcibly(0, DISCONNECT_TIMEOUT_MS, false) }
        runCatching { mqttClient.close(true) }
        synchronized(lifecycleLock) {
            liveClients -= mqttClient
            if (client === mqttClient) client = null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val OPERATION_TIMEOUT_MS = 5_000L
        const val DISCONNECT_TIMEOUT_MS = 500L
    }
}
