package com.far.mqttcall

import android.util.Log
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
import com.far.mqttcall.settings.KEY_SLOT_COUNT
import com.far.mqttcall.settings.SavedCallSettings
import com.far.mqttcall.transport.MqttEvent
import com.far.mqttcall.transport.MqttTransport
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class CallUiState(
    val broker: BrokerProfile = AppDefaults.defaultBroker,
    val channel: String = AppDefaults.defaultChannel,
    val keySlots: List<String> = AppDefaults.defaultKeySlots,
    val activeKeyIndex: Int = 0,
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
    val key: String get() = keySlots[activeKeyIndex]
}

sealed interface CallAction {
    data object Connect : CallAction
    data object Disconnect : CallAction
    data object GenerateKey : CallAction
    data class GenerateKeySlot(val slotIndex: Int) : CallAction
    data class ActivateKey(val slotIndex: Int) : CallAction
    data object ResetDefaults : CallAction
    data object PressTalk : CallAction
    data object ReleaseTalk : CallAction
    data object RequestMicrophone : CallAction
    data class SelectBroker(val broker: BrokerProfile) : CallAction
    data class UpdateChannel(val channel: String) : CallAction
    data class UpdateKey(val key: String) : CallAction
    data class UpdateKeySlot(val slotIndex: Int, val key: String) : CallAction
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
    private var playbackJob: Job? = null
    private var remoteFloorWatchJob: Job? = null
    private val talkMutex = Mutex()

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
            is CallAction.GenerateKeySlot -> generateKey(action.slotIndex)
            is CallAction.ActivateKey -> activateKey(action.slotIndex)
            CallAction.ResetDefaults -> resetDefaults()
            CallAction.PressTalk -> pressTalk()
            CallAction.ReleaseTalk -> releaseTalk()
            CallAction.RequestMicrophone -> {
                update { copy(microphoneGranted = true) }
                update { copy(canTalk = isConnectedAndFree()) }
            }
            is CallAction.SelectBroker -> update { copy(broker = action.broker) }
            is CallAction.UpdateChannel -> updateChannel(action.channel)
            is CallAction.UpdateKey -> updateKeySlot(_uiState.value.activeKeyIndex, action.key)
            is CallAction.UpdateKeySlot -> updateKeySlot(action.slotIndex, action.key)
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
                settingsStore.save(SavedCallSettings(state.broker, state.channel, state.keySlots, state.activeKeyIndex))
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
            releaseTalkNow()
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
        generateKey(_uiState.value.activeKeyIndex)
    }

    private fun generateKey(slotIndex: Int) {
        if (!canEditKeys()) return
        val bytes = ByteArray(12).also(random::nextBytes)
        updateKeySlot(slotIndex, "PTT-" + bytes.joinToString("") { "%02X".format(it) })
    }

    private fun activateKey(slotIndex: Int) {
        if (!canEditKeys()) return
        if (slotIndex !in 0 until KEY_SLOT_COUNT) return
        if (_uiState.value.keySlots[slotIndex].isBlank()) {
            update { copy(error = "Generate or enter a key before activating it") }
            return
        }
        update { copy(activeKeyIndex = slotIndex, error = null) }
        persistSettings()
    }

    private fun updateKeySlot(slotIndex: Int, key: String) {
        if (!canEditKeys() || slotIndex !in 0 until KEY_SLOT_COUNT) return
        val slots = _uiState.value.keySlots.toMutableList()
        slots[slotIndex] = key
        update { copy(keySlots = slots, error = null) }
        persistSettings()
    }

    private fun canEditKeys(): Boolean {
        if (_uiState.value.connection == ConnectionState.CONNECTED ||
            _uiState.value.connection == ConnectionState.CONNECTING
        ) {
            update { copy(error = "Disconnect before changing encryption keys") }
            return false
        }
        return true
    }

    private fun persistSettings() {
        val state = _uiState.value
        settingsStore.save(SavedCallSettings(state.broker, state.channel, state.keySlots, state.activeKeyIndex))
    }

    private fun resetDefaults() {
        val defaults = SavedCallSettings(
            broker = AppDefaults.defaultBroker,
            channel = AppDefaults.defaultChannel,
            keySlots = AppDefaults.defaultKeySlots,
            activeKeyIndex = 0,
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
        if (!floor.localClaim(sessionId, clockMs() + TALK_LEASE_MS)) {
            log("PTT press rejected: floor busy")
            return
        }
        update { copy(talkState = floor.state(), canTalk = false) }

        scope.launch {
            talkMutex.withLock {
                // A quick tap may have released the floor before this coroutine ran.
                if (floor.state() != TalkState.LOCAL_TALKING) return@withLock
                runCatching {
                    publishControl(CONTROL_CLAIM)
                    log("PTT claim published")
                    audioEngine.startCapture { frames ->
                        if (!floor.renewLocal(sessionId, clockMs() + TALK_LEASE_MS)) {
                            log("Audio batch dropped: local floor lost")
                            return@startCapture
                        }
                        publishAudio(encodeAudio(frames))
                    }
                }.onFailure { error ->
                    log("PTT start failed: ${error.message}")
                    floor.releaseLocal()
                    runCatching { audioEngine.stopCapture() }
                    update { copy(talkState = floor.state(), canTalk = isConnectedAndFree(), error = error.message) }
                }
            }
        }
    }

    private fun releaseTalk() {
        // Drop the floor immediately so a capture that is still starting sends nothing.
        floor.releaseLocal()
        scope.launch { releaseTalkNow() }
    }

    private suspend fun releaseTalkNow() {
        talkMutex.withLock {
            floor.releaseLocal()
            audioEngine.stopCapture()
            if (_uiState.value.connection == ConnectionState.CONNECTED) {
                runCatching { publishControl(CONTROL_RELEASE) }
                    .onSuccess { log("PTT release published, sent=${_uiState.value.sentBatches}") }
                    .onFailure { log("PTT release failed: ${it.message}") }
            }
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
        val decoded = cryptoSession?.decrypt(payload) ?: run {
            log("Dropped undecryptable packet (${payload.size} bytes)")
            return
        }
        val remoteSession = decoded.header.sessionId
        // MQTT 3.1.1 brokers echo our own publishes back to us; never treat them as remote.
        if (remoteSession.contentEquals(sessionId)) return
        when (decoded.header.kind) {
            com.far.mqttcall.protocol.PacketKind.CLAIM -> {
                decodeClaim(decoded.plaintext) ?: return
                // The sender's expiry uses its own wall clock; lease from our clock to tolerate skew.
                floor.onRemoteClaim(remoteSession, clockMs() + TALK_LEASE_MS)
                log("Remote claim received")
                watchRemoteFloor()
                refreshTalkState()
            }
            com.far.mqttcall.protocol.PacketKind.RELEASE -> {
                floor.onRemoteRelease(remoteSession)
                jitterBuffer.finish(remoteSession)
                log("Remote release received, received=${_uiState.value.receivedBatches}")
                startPlaybackIfReady()
                refreshTalkState()
            }
            com.far.mqttcall.protocol.PacketKind.AUDIO -> {
                val frames = decodeAudio(decoded.plaintext) ?: return
                floor.onRemoteAudio(remoteSession, clockMs() + TALK_LEASE_MS)
                jitterBuffer.offer(AudioBatch(remoteSession, decoded.header.sequence, frames))
                update { copy(receivedBatches = receivedBatches + 1, bufferState = jitterBuffer.state()) }
                startPlaybackIfReady()
                watchRemoteFloor()
                refreshTalkState()
            }
        }
    }

    /** Remote leases can lapse without a RELEASE (lost packet, dropped peer); re-enable PTT when they do. */
    private fun watchRemoteFloor() {
        if (remoteFloorWatchJob?.isActive == true) return
        remoteFloorWatchJob = scope.launch {
            while (floor.activeRemoteSession() != null) delay(FLOOR_WATCH_INTERVAL_MS)
            refreshTalkState()
        }
    }

    private fun startPlaybackIfReady() {
        if (jitterBuffer.state() != BufferState.PLAYING || playbackJob?.isActive == true) return
        playbackJob = scope.launch {
            while (true) {
                val frame = jitterBuffer.pollFrame() ?: break
                runCatching { audioEngine.play(AudioBatch(ByteArray(0), 0, listOf(frame))) }
                    .onFailure { log("Playback failed: ${it.message}") }
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
        runCatching { transport.publish(session.encrypt(header, payload), AUDIO_QOS) }
            .onSuccess { update { copy(sentBatches = sentBatches + 1) } }
            .onFailure {
                if (it is CancellationException) throw it
                log("Audio publish failed: ${it.message}")
            }
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

    private fun log(message: String) {
        // android.util.Log is unavailable in JVM unit tests.
        runCatching { Log.i(LOG_TAG, message) }
    }

    private fun update(transform: CallUiState.() -> CallUiState) {
        _uiState.value = _uiState.value.transform()
    }

    private fun SavedCallSettings.toUiState(): CallUiState = CallUiState(
        broker = broker,
        channel = channel,
        keySlots = keySlots,
        activeKeyIndex = activeKeyIndex,
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
        const val FLOOR_WATCH_INTERVAL_MS = 200L
        const val LOG_TAG = "MqttCall"
    }
}

private fun topicFor(channel: String): String = channelTopic(channel).getOrElse { "" }
