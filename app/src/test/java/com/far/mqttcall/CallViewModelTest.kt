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
import com.far.mqttcall.settings.SavedCallSettings
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
    fun `selecting a broker persists it immediately`() = runTest {
        val fixture = Fixture()
        val custom = BrokerProfile("Doorbell", "192.168.1.107", 1883, false, audioPacketIntervalUnits = 1)

        fixture.viewModel.dispatch(CallAction.SaveBroker(custom))

        assertEquals(custom, fixture.viewModel.uiState.value.broker)
        assertEquals(listOf(custom), fixture.viewModel.uiState.value.savedBrokers)
        assertEquals(custom, fixture.settings.load().broker)
        assertEquals(listOf(custom), fixture.settings.load().savedBrokers)
    }

    @Test
    fun `saved broker is restored by a new view model`() = runTest {
        val settings = InMemoryCallSettingsStore()
        val custom = BrokerProfile("Doorbell", "192.168.1.107", 1883, false, audioPacketIntervalUnits = 1)
        val first = Fixture(settings)
        first.viewModel.dispatch(CallAction.SaveBroker(custom))

        val second = Fixture(settings)

        assertEquals(custom, second.viewModel.uiState.value.broker)
        assertEquals(listOf(custom), second.viewModel.uiState.value.savedBrokers)
    }

    @Test
    fun `saving selecting and deleting channels persists the active choice`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.SaveChannel("1234567890123456"))
        assertEquals("1234567890123456", fixture.settings.load().channel)
        assertTrue(fixture.settings.load().savedChannels.contains("1234567890123456"))
        fixture.viewModel.dispatch(CallAction.SelectChannel("3344"))
        fixture.viewModel.dispatch(CallAction.DeleteChannel("1234567890123456"))
        assertEquals("3344", fixture.settings.load().channel)
        assertFalse(fixture.settings.load().savedChannels.contains("1234567890123456"))
    }

    @Test
    fun `random channel has sixteen ASCII digits and is saved and selected`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.GenerateChannel)
        val channel = fixture.settings.load().channel
        assertTrue(channel.matches(Regex("[1-9][0-9]{15}")))
        assertTrue(fixture.settings.load().savedChannels.contains(channel))
    }

    @Test
    fun `previously selected channel remains available after reopening`() = runTest {
        val settings = InMemoryCallSettingsStore(
            SavedCallSettings(AppDefaults.defaultBroker, "7788", AppDefaults.defaultKeySlots),
        )
        val fixture = Fixture(settings)
        assertTrue(fixture.viewModel.uiState.value.savedChannels.contains("7788"))
    }

    @Test
    fun `edited and deleted broker profiles update selected broker`() = runTest {
        val fixture = Fixture()
        val original = BrokerProfile("Office", "192.168.1.20", 1883, false)
        fixture.viewModel.dispatch(CallAction.SaveBroker(original))
        fixture.viewModel.dispatch(CallAction.EditBroker("Office", original.copy(name = "Home")))
        assertEquals("Home", fixture.settings.load().broker.name)
        assertEquals(1, fixture.settings.load().savedBrokers.size)
        fixture.viewModel.dispatch(CallAction.DeleteBroker("Home"))
        assertTrue(fixture.settings.load().savedBrokers.isEmpty())
        assertEquals(AppDefaults.defaultBroker, fixture.settings.load().broker)
    }

    @Test
    fun `editing broker without changing host preserves packet interval`() = runTest {
        val fixture = Fixture()
        val original = BrokerProfile("Office", "192.168.1.20", 1883, false, audioPacketIntervalUnits = 3)
        fixture.viewModel.dispatch(CallAction.SaveBroker(original))
        fixture.viewModel.dispatch(CallAction.EditBroker("Office", original.copy(port = 1884)))
        assertEquals(3, fixture.settings.load().broker.audioPacketIntervalUnits)
    }

    @Test
    fun `generate key changes only the key`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.GenerateKey)

        assertNotEquals(AppDefaults.defaultKey, fixture.viewModel.uiState.value.key)
        assertEquals(AppDefaults.defaultChannel, fixture.viewModel.uiState.value.channel)
    }

    @Test
    fun `inactive key slot can be generated and activated persistently`() = runTest {
        val fixture = Fixture()
        fixture.viewModel.dispatch(CallAction.GenerateKeySlot(1))

        val generated = fixture.viewModel.uiState.value.keySlots[1]
        assertTrue(generated.startsWith("PTT-"))
        assertEquals(AppDefaults.defaultKey, fixture.viewModel.uiState.value.key)
        assertEquals(generated, fixture.settings.load().keySlots[1])

        fixture.viewModel.dispatch(CallAction.ActivateKey(1))

        assertEquals(1, fixture.viewModel.uiState.value.activeKeyIndex)
        assertEquals(generated, fixture.viewModel.uiState.value.key)
        assertEquals(1, fixture.settings.load().activeKeyIndex)
    }

    @Test
    fun `selected LAN broker uses the high packet frequency`() = runTest {
        val broker = BrokerProfile("DietPi LAN", "192.168.1.107", 1886, false, audioPacketIntervalUnits = 1)
        val settings = InMemoryCallSettingsStore(
            SavedCallSettings(broker, AppDefaults.defaultChannel, AppDefaults.defaultKeySlots),
        )
        val fixture = Fixture(settings)
        fixture.connectWithMicrophone()

        fixture.viewModel.dispatch(CallAction.PressTalk)
        advanceUntilIdle()

        assertEquals(1, fixture.audio.captureIntervalUnits)
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

    private class Fixture(
        val settings: InMemoryCallSettingsStore = InMemoryCallSettingsStore(),
    ) {
        var nowMs = 0L
        val transport = FakeTransport()
        val audio = FakeAudioEngine()
        val viewModel = CallViewModel(
            transport = transport,
            cryptoEngine = JvmCryptoEngine(),
            audioEngine = audio,
            settingsStore = settings,
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
    var captureIntervalUnits: Int? = null

    private var onBatch: (suspend (List<ByteArray>) -> Unit)? = null

    override suspend fun startCapture(
        audioPacketIntervalUnits: Int,
        onBatch: suspend (List<ByteArray>) -> Unit,
    ) {
        started = true
        captureIntervalUnits = audioPacketIntervalUnits
        this.onBatch = onBatch
    }

    suspend fun emitBatch() {
        onBatch?.invoke(listOf(byteArrayOf(1, 2, 3)))
    }

    override suspend fun stopCapture() {
        stopped = true
    }

    override fun stopCaptureImmediately() {
        stopped = true
    }

    override suspend fun play(batch: AudioBatch) = Unit

    override suspend fun stopPlayback() = Unit
}

private fun claimExpiry(expiresAtMs: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(expiresAtMs).array()

private fun audioPayload(): ByteArray = byteArrayOf(1, 0, 3, 1, 2, 3)
