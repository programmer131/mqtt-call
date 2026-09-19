package com.far.mqttcall

import com.far.mqttcall.audio.AudioBatch
import com.far.mqttcall.audio.AudioEngine
import com.far.mqttcall.crypto.JvmCryptoEngine
import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.domain.BrokerProfile
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

    private class Fixture {
        val transport = FakeTransport()
        val audio = FakeAudioEngine()
        val viewModel = CallViewModel(
            transport = transport,
            cryptoEngine = JvmCryptoEngine(),
            audioEngine = audio,
            settingsStore = InMemoryCallSettingsStore(),
            sessionId = ByteArray(16) { 1 },
            clockMs = { 0L },
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
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

    override suspend fun startCapture(onBatch: suspend (List<ByteArray>) -> Unit) {
        started = true
    }

    override suspend fun stopCapture() {
        stopped = true
    }

    override suspend fun play(batch: AudioBatch) = Unit

    override suspend fun stopPlayback() = Unit
}
