package com.far.mqttcall

import com.far.mqttcall.audio.AudioBatch
import com.far.mqttcall.audio.AudioEngine
import com.far.mqttcall.crypto.JvmCryptoEngine
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.settings.InMemoryCallSettingsStore
import com.far.mqttcall.transport.MqttEvent
import com.far.mqttcall.transport.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallServiceTest {
    @Test
    fun `stop then connect while bound recreates owner resources and preserves state flow`() = runTest {
        val fixture = Fixture(this)
        fixture.connect()
        val oldViewModel = fixture.owner.viewModel
        val oldTransport = fixture.transport
        val oldAudio = fixture.audio
        val boundState = fixture.owner.uiState

        fixture.owner.stop()
        advanceUntilIdle()
        assertEquals(ConnectionState.DISCONNECTED, boundState.value.connection)
        fixture.owner.dispatch(CallAction.Connect)
        advanceUntilIdle()
        fixture.transport.events.emit(MqttEvent.Connected)
        advanceUntilIdle()

        assertSame(boundState, fixture.owner.uiState)
        assertNotSame(oldViewModel, fixture.owner.viewModel)
        assertNotSame(oldTransport, fixture.transport)
        assertNotSame(oldAudio, fixture.audio)
        assertTrue(fixture.calls.indexOf("service-start") < fixture.calls.indexOf("connect"))
        assertEquals(ConnectionState.CONNECTED, boundState.value.connection)
        assertEquals(1, fixture.calls.count { it == "connect" })
        fixture.owner.destroy()
        advanceUntilIdle()
    }

    @Test
    fun `stalled capture startup cannot delay playback cleanup disconnect or service stop`() = runTest {
        val fixture = Fixture(this)
        fixture.connect()
        val gate = CompletableDeferred<Unit>()
        fixture.audio.startGate = gate
        fixture.owner.dispatch(CallAction.PressTalk)
        runCurrent()

        fixture.owner.stop()
        advanceTimeBy(2_500)
        runCurrent()
        try {
            assertTrue("capture-immediate" in fixture.calls)
            assertTrue("playback-stop" in fixture.calls)
            assertTrue("disconnect" in fixture.calls)
            assertTrue("transport-abort" in fixture.calls)
            assertTrue("service-stop" in fixture.calls)
            assertEquals(ConnectionState.DISCONNECTED, fixture.owner.uiState.value.connection)
        } finally {
            gate.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun `stalled cleanup operations cannot prevent terminal service stop`() = runTest {
        val fixture = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.transport.disconnectGate = gate
        fixture.audio.stopGate = gate

        fixture.owner.stop()
        advanceTimeBy(2_500)
        runCurrent()
        try {
            assertTrue("capture-stop" in fixture.calls)
            assertTrue("playback-stop" in fixture.calls)
            assertTrue("disconnect" in fixture.calls)
            assertTrue("transport-abort" in fixture.calls)
            assertEquals(1, fixture.calls.count { it == "service-stop" })
            fixture.owner.destroy()
            fixture.owner.dispatch(CallAction.Connect)
            assertFalse("connect" in fixture.calls)
        } finally {
            gate.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun `owner retains one view model and does not connect or capture at startup`() = runTest {
        val fixture = Fixture(this)
        val first = fixture.owner.viewModel
        first.dispatch(CallAction.UpdateChannel("7788"))
        runCurrent()

        assertSame(first, fixture.owner.viewModel)
        assertEquals("7788", fixture.owner.uiState.value.channel)
        assertEquals(1, fixture.transport.events.subscriptionCount.value)
        advanceUntilIdle()
        assertTrue(fixture.calls.isEmpty())
        fixture.owner.destroy()
        advanceUntilIdle()
    }

    @Test
    fun `destroy immediately stops capture then disconnects and releases playback once`() = runTest {
        val fixture = Fixture(this)
        fixture.settings.setKeepConnected(true)

        fixture.owner.destroy()
        assertEquals(listOf("capture-immediate"), fixture.calls)
        fixture.owner.destroy()
        advanceUntilIdle()

        assertEquals(1, fixture.calls.count { it == "capture-stop" })
        assertEquals(1, fixture.calls.count { it == "playback-stop" })
        assertEquals(1, fixture.calls.count { it == "disconnect" })
        assertEquals(0, fixture.transport.events.subscriptionCount.value)
        assertTrue(fixture.settings.load().keepConnected)
    }

    @Test
    fun `stop clears restart preference and completes cleanup before stopping service`() = runTest {
        val fixture = Fixture(this)
        fixture.settings.setKeepConnected(true)
        fixture.settings.markMicrophonePermissionPrompted()
        assertTrue(fixture.owner.shouldRestart)

        fixture.owner.stop()
        assertFalse(fixture.settings.load().keepConnected)
        assertFalse(fixture.owner.shouldRestart)
        advanceUntilIdle()

        assertTrue(fixture.settings.load().microphonePermissionPrompted)
        assertTrue("disconnect" in fixture.calls)
        assertTrue("playback-stop" in fixture.calls)
        assertTrue(fixture.calls.indexOf("disconnect") < fixture.calls.indexOf("service-stop"))
        assertTrue(fixture.calls.indexOf("playback-stop") < fixture.calls.indexOf("service-stop"))
        assertEquals("service-stop", fixture.calls.last())
    }

    @Test
    fun `binder disconnect follows the stop path`() = runTest {
        val fixture = Fixture(this)
        fixture.settings.setKeepConnected(true)

        fixture.owner.dispatch(CallAction.Disconnect)
        advanceUntilIdle()

        assertFalse(fixture.settings.load().keepConnected)
        assertEquals(1, fixture.calls.count { it == "service-stop" })
        assertTrue("disconnect" in fixture.calls)
    }

    @Test
    fun `restart policy reads the persisted flag without starting a call`() = runTest {
        val fixture = Fixture(this)
        assertFalse(fixture.owner.shouldRestart)
        fixture.settings.setKeepConnected(true)
        assertTrue(fixture.owner.shouldRestart)
        assertTrue(fixture.calls.isEmpty())
        fixture.owner.destroy()
        advanceUntilIdle()
    }

    @Test
    fun `cleanup still disconnects when audio cleanup fails`() = runTest {
        val fixture = Fixture(this)
        fixture.audio.failStop = true

        fixture.owner.stop()
        advanceUntilIdle()

        assertTrue("playback-stop" in fixture.calls)
        assertTrue("disconnect" in fixture.calls)
        assertEquals("service-stop", fixture.calls.last())
    }

    @Test
    fun `ptt promotes microphone before capture and demotes after release`() = runTest {
        val fixture = Fixture(this)
        fixture.connect()

        fixture.owner.dispatch(CallAction.PressTalk)
        advanceUntilIdle()
        assertTrue(fixture.calls.indexOf("microphone-on") < fixture.calls.indexOf("capture-start"))
        assertTrue("capture-start" in fixture.calls)

        fixture.owner.dispatch(CallAction.ReleaseTalk)
        advanceUntilIdle()
        assertTrue(fixture.calls.indexOf("capture-stop") < fixture.calls.indexOf("microphone-off"))
        fixture.owner.destroy()
        advanceUntilIdle()
    }

    @Test
    fun `platform permission denial prevents capture despite view model permission state`() = runTest {
        val fixture = Fixture(this)
        fixture.connect()
        fixture.permissionGranted = false

        fixture.owner.dispatch(CallAction.PressTalk)
        advanceUntilIdle()

        assertFalse("capture-start" in fixture.calls)
        assertFalse("microphone-on" in fixture.calls)
        assertTrue(fixture.owner.uiState.value.error!!.contains("Microphone"))
        fixture.owner.destroy()
        advanceUntilIdle()
    }

    @Test
    fun `capture startup failure restores media playback mode`() = runTest {
        val fixture = Fixture(this)
        fixture.connect()
        fixture.audio.failStart = true

        fixture.owner.dispatch(CallAction.PressTalk)
        advanceUntilIdle()

        assertTrue(fixture.calls.indexOf("microphone-on") < fixture.calls.indexOf("microphone-off"))
        assertFalse(fixture.owner.uiState.value.error.isNullOrBlank())
        fixture.owner.destroy()
        advanceUntilIdle()
    }

    @Test
    fun `destroy stops a late capture startup after cleanup has begun`() = runTest {
        val fixture = Fixture(this)
        fixture.connect()
        val gate = CompletableDeferred<Unit>()
        fixture.audio.startGate = gate
        fixture.owner.dispatch(CallAction.PressTalk)
        runCurrent()

        fixture.owner.destroy()
        runCurrent()
        assertTrue("capture-immediate" in fixture.calls)
        assertTrue("disconnect" in fixture.calls)
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(fixture.calls.indexOf("capture-start") < fixture.calls.lastIndexOf("capture-immediate"))
        assertTrue("capture-start" in fixture.calls)
        assertTrue("disconnect" in fixture.calls)
    }

    private class Fixture(private val testScope: TestScope) {
        val calls = mutableListOf<String>()
        val settings = InMemoryCallSettingsStore()
        lateinit var transport: FakeTransport
        lateinit var audio: FakeAudio
        var permissionGranted = true
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val owner = CallServiceLifecycle(
            createDependencies = {
                transport = FakeTransport(calls)
                audio = FakeAudio(calls)
                CallServiceDependencies(transport, JvmCryptoEngine(), audio) {
                    calls += "transport-abort"
                }
            },
            settingsStore = settings,
            canRecord = { permissionGranted },
            onCaptureChanged = { calls += if (it) "microphone-on" else "microphone-off" },
            onStopped = { calls += "service-stop" },
            onStarted = { calls += "service-start" },
            viewModelDispatcher = dispatcher,
            cleanupDispatcher = dispatcher,
            cleanupScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        suspend fun connect() {
            owner.dispatch(CallAction.Connect)
            testScope.advanceUntilIdle()
            transport.events.emit(MqttEvent.Connected)
            owner.dispatch(CallAction.RequestMicrophone)
            testScope.advanceUntilIdle()
            calls.clear()
        }
    }

    private class FakeTransport(private val calls: MutableList<String>) : MqttTransport {
        var disconnectGate: CompletableDeferred<Unit>? = null
        override val events = MutableSharedFlow<MqttEvent>(extraBufferCapacity = 8)
        override suspend fun connect(profile: BrokerProfile, topic: String) { calls += "connect" }
        override suspend fun publish(payload: ByteArray, qos: Int) { calls += "publish" }
        override suspend fun disconnect() {
            calls += "disconnect"
            withContext(NonCancellable) { disconnectGate?.await() }
        }
    }

    private class FakeAudio(private val calls: MutableList<String>) : AudioEngine {
        var failStart = false
        var failStop = false
        var startGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        override suspend fun startCapture(
            audioPacketIntervalUnits: Int,
            onBatch: suspend (List<ByteArray>) -> Unit,
        ) {
            withContext(NonCancellable) {
                startGate?.await()
                calls += "capture-start"
            }
            check(!failStart) { "Capture failed" }
        }
        override suspend fun stopCapture() {
            calls += "capture-stop"
            withContext(NonCancellable) { stopGate?.await() }
            check(!failStop) { "Capture cleanup failed" }
        }
        override fun stopCaptureImmediately() { calls += "capture-immediate" }
        override suspend fun play(batch: AudioBatch) = Unit
        override suspend fun stopPlayback() { calls += "playback-stop" }
    }
}
