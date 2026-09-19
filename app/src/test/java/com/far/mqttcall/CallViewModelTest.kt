package com.far.mqttcall

import com.far.mqttcall.audio.AudioBatch
import com.far.mqttcall.audio.AudioEngine
import com.far.mqttcall.crypto.JvmCryptoEngine
import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.floor.TalkState
import com.far.mqttcall.protocol.PacketHeader
import com.far.mqttcall.protocol.PacketKind
import java.nio.ByteBuffer
import com.far.mqttcall.settings.InMemoryCallSettingsStore
import com.far.mqttcall.transport.MqttEvent
import com.far.mqttcall.transport.MqttTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    @Test
    fun `initial state contains shared static defaults`() = runTest {
        val fixture = Fixture()

        assertEquals(AppDefaults.defaultBroker, fixture.viewModel.uiState.value.broker)
        assertEquals(AppDefaults.defaultChannel, fixture.viewModel.uiState.value.channel)
        assertEquals(AppDefaults.defaultKey, fixture.viewModel.uiState.value.key)
        assertEquals("call/channel/3344", fixture.viewModel.uiState.value.topic)
    }

    @Test
    fun `generate key changes only the key`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.GenerateKey)

        assertNotEquals(AppDefaults.defaultKey, fixture.viewModel.uiState.value.key)
        assertEquals(AppDefaults.defaultChannel, fixture.viewModel.uiState.value.channel)
    }

    @Test
    fun `connect does not enable ptt until transport subscription succeeds`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.Connect)
        advanceUntilIdle()

        assertFalse(fixture.viewModel.uiState.value.canTalk)
        fixture.transport.incoming.emit(MqttEvent.Connected)
        fixture.viewModel.dispatch(CallAction.RequestMicrophone)
        advanceUntilIdle()
        assertTrue(fixture.viewModel.uiState.value.canTalk)
    }

    @Test
    fun `press publishes claim before audio and release stops capture`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.Connect)
        advanceUntilIdle()
        fixture.transport.incoming.emit(MqttEvent.Connected)
        fixture.viewModel.dispatch(CallAction.RequestMicrophone)
        advanceUntilIdle()

        fixture.viewModel.dispatch(CallAction.PressTalk)
        advanceUntilIdle()
        assertEquals(1, fixture.transport.published.size)
        assertEquals(1, fixture.transport.qos.first())
        assertTrue(fixture.audio.started)

        fixture.viewModel.dispatch(CallAction.ReleaseTalk)
        advanceUntilIdle()
        assertEquals(2, fixture.transport.published.size)
        assertEquals(1, fixture.transport.qos.last())
        assertTrue(fixture.audio.stopped)
    }

    @Test
    fun `release publishes release after local lease has expired`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.Connect)
        advanceUntilIdle()
        fixture.transport.incoming.emit(MqttEvent.Connected)
        fixture.viewModel.dispatch(CallAction.RequestMicrophone)
        advanceUntilIdle()

        fixture.viewModel.dispatch(CallAction.PressTalk)
        advanceUntilIdle()
        fixture.nowMs = 1_001

        fixture.viewModel.dispatch(CallAction.ReleaseTalk)
        advanceUntilIdle()

        assertEquals(2, fixture.transport.published.size)
        assertTrue(fixture.audio.stopped)
    }

    @Test
    fun `audio keeps flowing past the initial one second lease`() = runTest {
        val fixture = Fixture()
        fixture.connectWithMicrophone()

        fixture.viewModel.dispatch(CallAction.PressTalk)
        advanceUntilIdle()
        repeat(10) {
            fixture.nowMs += 200
            fixture.audio.emitBatch()
        }

        assertEquals(10L, fixture.viewModel.uiState.value.sentBatches)
    }

    @Test
    fun `own echoed packets are ignored`() = runTest {
        val fixture = Fixture()
        fixture.connectWithMicrophone()
        fixture.viewModel.dispatch(CallAction.PressTalk)
        advanceUntilIdle()
        fixture.audio.emitBatch()
        fixture.viewModel.dispatch(CallAction.ReleaseTalk)
        advanceUntilIdle()

        fixture.transport.published.toList().forEach {
            fixture.transport.incoming.emit(MqttEvent.Message(it))
        }
        advanceUntilIdle()

        assertEquals(0L, fixture.viewModel.uiState.value.receivedBatches)
        assertEquals(TalkState.IDLE, fixture.viewModel.uiState.value.talkState)
        assertTrue(fixture.viewModel.uiState.value.canTalk)
    }

    @Test
    fun `remote claim with a skewed clock still blocks then release frees the floor`() = runTest {
        val fixture = Fixture()
        fixture.connectWithMicrophone()

        // The peer's clock is far behind ours, so its absolute expiry is already in our past.
        fixture.nowMs = 1_000_000
        fixture.transport.incoming.emit(MqttEvent.Message(fixture.peerPacket(PacketKind.CLAIM, 1, claimExpiry(0))))
        advanceUntilIdle()
        assertEquals(TalkState.REMOTE_TALKING, fixture.viewModel.uiState.value.talkState)
        assertFalse(fixture.viewModel.uiState.value.canTalk)

        fixture.transport.incoming.emit(MqttEvent.Message(fixture.peerPacket(PacketKind.AUDIO, 2, audioPayload())))
        fixture.transport.incoming.emit(MqttEvent.Message(fixture.peerPacket(PacketKind.RELEASE, 3, ByteArray(0))))
        advanceUntilIdle()

        assertEquals(1L, fixture.viewModel.uiState.value.receivedBatches)
        assertEquals(TalkState.IDLE, fixture.viewModel.uiState.value.talkState)
        assertTrue(fixture.viewModel.uiState.value.canTalk)
    }

    private class Fixture {
        var nowMs = 0L
        val transport = FakeTransport()
        val audio = FakeAudioEngine()
        val viewModel = CallViewModel(
            transport = transport,
            cryptoEngine = JvmCryptoEngine(),
            audioEngine = audio,
            settingsStore = InMemoryCallSettingsStore(),
            sessionId = ByteArray(16) { 1 },
            clockMs = { nowMs },
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        )
        private val peer = JvmCryptoEngine().prepare(AppDefaults.defaultChannel, AppDefaults.defaultKey.toCharArray())

        suspend fun connectWithMicrophone() {
            viewModel.dispatch(CallAction.Connect)
            transport.incoming.emit(MqttEvent.Connected)
            viewModel.dispatch(CallAction.RequestMicrophone)
        }

        fun peerPacket(kind: PacketKind, sequence: Long, plaintext: ByteArray): ByteArray = peer.encrypt(
            PacketHeader(kind, ByteArray(16) { 2 }, sequence, ByteArray(12)),
            plaintext,
        )
    }
}

private class FakeTransport : MqttTransport {
    val incoming = MutableSharedFlow<MqttEvent>(replay = 1, extraBufferCapacity = 16)
    val published = mutableListOf<ByteArray>()
    val qos = mutableListOf<Int>()
    override val events: Flow<MqttEvent> = incoming

    override suspend fun connect(profile: BrokerProfile, topic: String) = Unit

    override suspend fun publish(payload: ByteArray, qos: Int) {
        published += payload
        this.qos += qos
    }

    override suspend fun disconnect() = Unit
}

private class FakeAudioEngine : AudioEngine {
    var started = false
    var stopped = false

    private var onBatch: (suspend (List<ByteArray>) -> Unit)? = null

    override suspend fun startCapture(onBatch: suspend (List<ByteArray>) -> Unit) {
        started = true
        this.onBatch = onBatch
    }

    suspend fun emitBatch() {
        onBatch?.invoke(listOf(byteArrayOf(1, 2, 3)))
    }

    override suspend fun stopCapture() {
        stopped = true
    }

    override suspend fun play(batch: AudioBatch) = Unit

    override suspend fun stopPlayback() = Unit
}

private fun claimExpiry(expiresAtMs: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(expiresAtMs).array()

private fun audioPayload(): ByteArray = byteArrayOf(1, 0, 3, 1, 2, 3)
