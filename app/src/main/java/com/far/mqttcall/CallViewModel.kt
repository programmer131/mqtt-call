package com.far.mqttcall

import androidx.lifecycle.ViewModel
import com.far.mqttcall.audio.AudioBatch
import com.far.mqttcall.audio.AudioEngine
import com.far.mqttcall.audio.BufferState
import com.far.mqttcall.audio.JitterBuffer
import com.far.mqttcall.crypto.CryptoEngine
import com.far.mqttcall.crypto.CryptoSession
import com.far.mqttcall.crypto.SecurityLevel
import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.domain.channelTopic
import com.far.mqttcall.domain.defaultBrokerProfiles
import com.far.mqttcall.floor.TalkFloor
import com.far.mqttcall.floor.TalkState
import com.far.mqttcall.settings.CallSettingsStore
import com.far.mqttcall.settings.SavedCallSettings
import com.far.mqttcall.transport.MqttEvent
import com.far.mqttcall.transport.MqttTransport
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class CallUiState(
    val broker: BrokerProfile = AppDefaults.defaultBroker,
    val channel: String = AppDefaults.defaultChannel,
    val key: String = AppDefaults.defaultKey,
    val topic: String = topicFor(AppDefaults.defaultChannel),
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val talkState: TalkState = TalkState.IDLE,
    val bufferState: BufferState = BufferState.BUFFERING,
    val securityLevel: SecurityLevel? = null,
    val microphoneGranted: Boolean = false,
    val canTalk: Boolean = false,
    val error: String? = null,
    val sentBatches: Long = 0,
    val receivedBatches: Long = 0,
) {
    val isConnected: Boolean get() = connection == ConnectionState.CONNECTED
}

sealed interface CallAction {
    data object Connect : CallAction
    data object Disconnect : CallAction
    data object GenerateKey : CallAction
    data object ResetDefaults : CallAction
    data object PressTalk : CallAction
    data object ReleaseTalk : CallAction
    data object RequestMicrophone : CallAction
    data class SelectBroker(val broker: BrokerProfile) : CallAction
    data class UpdateChannel(val channel: String) : CallAction
    data class UpdateKey(val key: String) : CallAction
}

class CallViewModel(
    private val transport: MqttTransport,
    private val cryptoEngine: CryptoEngine,
    private val audioEngine: AudioEngine,
    private val settingsStore: CallSettingsStore,
    private val sessionId: ByteArray = ByteArray(SESSION_ID_LENGTH).also(SecureRandom()::nextBytes),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ViewModel() {
    private val random = SecureRandom()
    private val sequence = AtomicLong(0)
    private val floor = TalkFloor(clockMs)
    private val jitterBuffer = JitterBuffer()
    private val _uiState = MutableStateFlow(settingsStore.load().toUiState())
    private var cryptoSession: CryptoSession? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        require(sessionId.size == SESSION_ID_LENGTH) { "Session ID must be 16 bytes" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect(::onTransportEvent)
        }
    }

    fun dispatch(action: CallAction) {
        when (action) {
            CallAction.Connect -> connect()
            CallAction.Disconnect -> disconnect()
            CallAction.GenerateKey -> generateKey()
            CallAction.ResetDefaults -> resetDefaults()
            CallAction.PressTalk -> pressTalk()
            CallAction.ReleaseTalk -> releaseTalk()
            CallAction.RequestMicrophone -> {
                update { copy(microphoneGranted = true) }
                update { copy(canTalk = isConnectedAndFree()) }
            }
            is CallAction.SelectBroker -> update { copy(broker = action.broker) }
            is CallAction.UpdateChannel -> updateChannel(action.channel)
            is CallAction.UpdateKey -> update { copy(key = action.key) }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private fun connect() {
        if (_uiState.value.connection == ConnectionState.CONNECTING ||
            _uiState.value.connection == ConnectionState.CONNECTED
        ) return

        val state = _uiState.value
        val topic = channelTopic(state.channel).getOrNull()
        if (topic == null || state.key.isBlank()) {
            update { copy(connection = ConnectionState.ERROR, error = "Enter a valid channel and key") }
            return
        }

        update { copy(connection = ConnectionState.CONNECTING, error = null) }
        scope.launch {
            runCatching {
                cryptoSession = cryptoEngine.prepare(state.channel, state.key.toCharArray())
                transport.connect(state.broker, topic)
                settingsStore.save(SavedCallSettings(state.broker, state.channel, state.key))
            }.onFailure { error ->
                update {
                    copy(
                        connection = ConnectionState.ERROR,
                        error = error.message ?: "Connection failed",
                        securityLevel = cryptoEngine.securityLevel,
                    )
                }
            }
        }
    }

    private fun disconnect() {
        scope.launch {
            releaseTalk()
            transport.disconnect()
            cryptoSession = null
            update {
                copy(
                    connection = ConnectionState.DISCONNECTED,
                    talkState = TalkState.IDLE,
                    bufferState = BufferState.BUFFERING,
                    canTalk = false,
                )
            }
        }
    }

    private fun generateKey() {
        val bytes = ByteArray(12).also(random::nextBytes)
        update { copy(key = "PTT-" + bytes.joinToString("") { "%02X".format(it) }) }
    }

    private fun resetDefaults() {
        val defaults = SavedCallSettings(
            broker = AppDefaults.defaultBroker,
            channel = AppDefaults.defaultChannel,
            key = AppDefaults.defaultKey,
        )
        settingsStore.save(defaults)
        update {
            defaults.toUiState().copy(
                microphoneGranted = microphoneGranted,
                connection = ConnectionState.DISCONNECTED,
            )
        }
    }

    private fun updateChannel(channel: String) {
        update {
            copy(
                channel = channel,
                topic = topicFor(channel),
            )
        }
    }

    private fun pressTalk() {
        val state = _uiState.value
        if (!state.canTalk || !state.microphoneGranted) return
        if (!floor.localClaim(sessionId, clockMs() + TALK_LEASE_MS)) return

        scope.launch {
            runCatching {
                publishControl(CONTROL_CLAIM)
                captureJob?.cancel()
                captureJob = scope.launch {
                    audioEngine.startCapture { frames ->
                        if (floor.state() != TalkState.LOCAL_TALKING) return@startCapture
                        val payload = encodeAudio(frames)
                        publishAudio(payload)
                    }
                }
                update { copy(talkState = floor.state(), canTalk = false) }
            }.onFailure { error ->
                floor.releaseLocal()
                update { copy(talkState = floor.state(), error = error.message) }
            }
        }
    }

    private fun releaseTalk() {
        if (floor.state() != TalkState.LOCAL_TALKING) {
            scope.launch { audioEngine.stopCapture() }
            return
        }
        scope.launch {
            runCatching { publishControl(CONTROL_RELEASE) }
            audioEngine.stopCapture()
            floor.releaseLocal()
            captureJob?.cancel()
            captureJob = null
            update { copy(talkState = floor.state(), canTalk = isConnectedAndFree()) }
        }
    }

    private suspend fun onTransportEvent(event: MqttEvent) {
        when (event) {
            MqttEvent.Connected -> update {
                copy(
                    connection = ConnectionState.CONNECTED,
                    securityLevel = cryptoEngine.securityLevel,
                    error = null,
                    canTalk = microphoneGranted && floor.canTransmit(),
                )
            }
            MqttEvent.Disconnected -> update {
                copy(connection = ConnectionState.DISCONNECTED, canTalk = false)
            }
            is MqttEvent.Error -> update {
                copy(connection = ConnectionState.ERROR, canTalk = false, error = event.message)
            }
            is MqttEvent.Message -> handleMessage(event.payload)
        }
    }

    private suspend fun handleMessage(payload: ByteArray) {
        val decoded = cryptoSession?.decrypt(payload) ?: return
        when (decoded.header.kind) {
            com.far.mqttcall.protocol.PacketKind.CLAIM -> {
                val expiresAt = decodeClaim(decoded.plaintext) ?: return
                floor.onRemoteClaim(decoded.header.sessionId, expiresAt)
                refreshTalkState()
            }
            com.far.mqttcall.protocol.PacketKind.RELEASE -> {
                floor.onRemoteRelease(decoded.header.sessionId)
                refreshTalkState()
            }
            com.far.mqttcall.protocol.PacketKind.AUDIO -> {
                val frames = decodeAudio(decoded.plaintext) ?: return
                floor.onRemoteAudio(decoded.header.sessionId)
                jitterBuffer.offer(AudioBatch(decoded.header.sessionId, decoded.header.sequence, frames))
                update { copy(receivedBatches = receivedBatches + 1, bufferState = jitterBuffer.state()) }
                startPlaybackIfReady(decoded.header.sessionId, decoded.header.sequence)
                refreshTalkState()
            }
        }
    }

    private fun startPlaybackIfReady(sessionId: ByteArray, sequence: Long) {
        if (jitterBuffer.state() != BufferState.PLAYING || playbackJob?.isActive == true) return
        playbackJob = scope.launch {
            while (jitterBuffer.state() == BufferState.PLAYING) {
                val frame = jitterBuffer.pollFrame() ?: break
                audioEngine.play(AudioBatch(sessionId, sequence, listOf(frame)))
            }
            update { copy(bufferState = jitterBuffer.state()) }
        }
    }

    private suspend fun publishControl(kind: Byte) {
        val session = cryptoSession ?: error("Encryption is not ready")
        val header = com.far.mqttcall.protocol.PacketHeader(
            kind = packetKind(kind),
            sessionId = this.sessionId,
            sequence = sequence.getAndIncrement(),
            nonce = ByteArray(NONCE_LENGTH),
        )
        val plaintext = if (kind == CONTROL_CLAIM) {
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(clockMs() + TALK_LEASE_MS).array()
        } else {
            ByteArray(0)
        }
        transport.publish(session.encrypt(header, plaintext), CONTROL_QOS)
    }

    private suspend fun publishAudio(payload: ByteArray) {
        val session = cryptoSession ?: return
        val header = com.far.mqttcall.protocol.PacketHeader(
            kind = com.far.mqttcall.protocol.PacketKind.AUDIO,
            sessionId = this.sessionId,
            sequence = sequence.getAndIncrement(),
            nonce = ByteArray(NONCE_LENGTH),
        )
        transport.publish(session.encrypt(header, payload), AUDIO_QOS)
        update { copy(sentBatches = sentBatches + 1) }
    }

    private fun encodeAudio(frames: List<ByteArray>): ByteArray {
        require(frames.size in 1..MAX_FRAMES_PER_BATCH)
        val size = 1 + frames.sumOf { 2 + it.size }
        val buffer = ByteBuffer.allocate(size).put(frames.size.toByte())
        frames.forEach { frame ->
            require(frame.size <= 0xFFFF)
            buffer.putShort(frame.size.toShort()).put(frame)
        }
        return buffer.array()
    }

    private fun decodeAudio(payload: ByteArray): List<ByteArray>? = runCatching {
        val buffer = ByteBuffer.wrap(payload)
        val count = buffer.get().toInt() and 0xFF
        require(count in 1..MAX_FRAMES_PER_BATCH)
        buildList(count) {
            repeat(count) {
                val size = buffer.short.toInt() and 0xFFFF
                require(size > 0 && size <= buffer.remaining())
                add(ByteArray(size).also(buffer::get))
            }
            require(!buffer.hasRemaining())
        }
    }.getOrNull()

    private fun decodeClaim(payload: ByteArray): Long? = runCatching {
        require(payload.size == Long.SIZE_BYTES)
        ByteBuffer.wrap(payload).long
    }.getOrNull()

    private fun refreshTalkState() {
        update { copy(talkState = floor.state(), canTalk = isConnectedAndFree()) }
    }

    private fun isConnectedAndFree(): Boolean =
        _uiState.value.connection == ConnectionState.CONNECTED &&
            _uiState.value.microphoneGranted && floor.canTransmit()

    private fun packetKind(kind: Byte): com.far.mqttcall.protocol.PacketKind = when (kind) {
        CONTROL_CLAIM -> com.far.mqttcall.protocol.PacketKind.CLAIM
        CONTROL_RELEASE -> com.far.mqttcall.protocol.PacketKind.RELEASE
        else -> error("Unsupported control packet")
    }

    private fun update(transform: CallUiState.() -> CallUiState) {
        _uiState.value = _uiState.value.transform()
    }

    private fun SavedCallSettings.toUiState(): CallUiState = CallUiState(
        broker = broker,
        channel = channel,
        key = key,
        topic = topicFor(channel),
    )

    private companion object {
        const val SESSION_ID_LENGTH = 16
        const val NONCE_LENGTH = 12
        const val TALK_LEASE_MS = 1_000L
        const val CONTROL_CLAIM: Byte = 1
        const val CONTROL_RELEASE: Byte = 2
        const val CONTROL_QOS = 1
        const val AUDIO_QOS = 0
        const val MAX_FRAMES_PER_BATCH = 10
    }
}

private fun topicFor(channel: String): String = channelTopic(channel).getOrElse { "" }
