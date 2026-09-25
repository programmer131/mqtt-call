package com.far.mqttcall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.far.mqttcall.audio.AndroidAudioEngine
import com.far.mqttcall.audio.AudioBatch
import com.far.mqttcall.audio.AudioEngine
import com.far.mqttcall.audio.BufferState
import com.far.mqttcall.crypto.AndroidCryptoEngine
import com.far.mqttcall.crypto.CryptoEngine
import com.far.mqttcall.floor.TalkState
import com.far.mqttcall.settings.AndroidCallSettingsStore
import com.far.mqttcall.settings.CallSettingsStore
import com.far.mqttcall.transport.MqttTransport
import com.far.mqttcall.transport.PahoMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CallService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var owner: CallServiceLifecycle
    private val binder = LocalBinder()
    private var microphoneActive = false
    private var destroyed = false
    private var foregroundEnabled = true

    inner class LocalBinder : Binder() {
        val viewModel: CallViewModel get() = owner.viewModel
        val uiState: StateFlow<CallUiState> get() = owner.uiState

        /** Route UI actions here so Disconnect also stops the service. */
        fun dispatch(action: CallAction) = owner.dispatch(action)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.call_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        // Meet the foreground deadline before settings, crypto, or MQTT initialization.
        updateForeground(CallUiState())
        owner = CallServiceLifecycle(
            createDependencies = {
                val transport = PahoMqttTransport()
                CallServiceDependencies(
                    transport,
                    AndroidCryptoEngine(applicationContext),
                    AndroidAudioEngine(applicationContext),
                    transport::abort,
                )
            },
            settingsStore = AndroidCallSettingsStore(applicationContext),
            canRecord = {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            onCaptureChanged = { active ->
                withContext(Dispatchers.Main.immediate) {
                    if (!destroyed) {
                        microphoneActive = active
                        updateForeground(owner.uiState.value)
                    }
                }
            },
            onStopped = {
                foregroundEnabled = false
                microphoneActive = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            onStarted = {
                // stopSelf does not destroy a service that still has an Activity bound to it.
                foregroundEnabled = true
                ContextCompat.startForegroundService(this, Intent(this, CallService::class.java))
                updateForeground(owner.uiState.value)
            },
        )
        serviceScope.launch {
            owner.uiState
                .map { Triple(it.broker, it.channel, it.connection) }
                .distinctUntilChanged()
                .collect { updateForeground(owner.uiState.value) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            owner.stop()
            return START_NOT_STICKY
        }
        owner.resumePersistedCall()
        return if (owner.shouldRestart) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        serviceScope.cancel()
        if (::owner.isInitialized) owner.destroy()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun updateForeground(state: CallUiState) {
        if (destroyed || !foregroundEnabled) return
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            if (microphoneActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state), types)
    }

    private fun notification(state: CallUiState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val status = getString(when (state.connection) {
            ConnectionState.DISCONNECTED -> R.string.call_disconnected
            ConnectionState.CONNECTING -> R.string.call_connecting
            ConnectionState.CONNECTED -> R.string.call_connected
            ConnectionState.ERROR -> R.string.call_error
        })
        val detail = getString(
            R.string.call_notification_text,
            state.broker.name, state.broker.host, state.channel, status,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.call_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.far.mqttcall.action.STOP"
        private const val CHANNEL_ID = "mqtt_call"
        private const val NOTIFICATION_ID = 1
    }
}

internal data class CallServiceDependencies(
    val transport: MqttTransport,
    val cryptoEngine: CryptoEngine,
    val audioEngine: AudioEngine,
    /** Must return immediately; fence new work and schedule any blocking force-close off-thread. */
    val abortTransport: () -> Unit,
)

/** Service-owned resources, separated from Android callbacks for JVM lifecycle tests. */
internal class CallServiceLifecycle(
    private val createDependencies: () -> CallServiceDependencies,
    private val settingsStore: CallSettingsStore,
    private val canRecord: () -> Boolean,
    private val onCaptureChanged: suspend (Boolean) -> Unit,
    private val onStopped: () -> Unit,
    private val onStarted: () -> Unit,
    private val viewModelDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private enum class Phase { RUNNING, STOPPING, STOPPED, DESTROYED }
    @Volatile private var phase = Phase.RUNNING
    private var reconnectPending = false
    private var restartChecked = false
    @Volatile private var generation = 0
    private val stateLock = Any()
    private val state = MutableStateFlow(CallUiState())
    // Remains stable for collectors held by an Activity across Stop -> Connect.
    val uiState: StateFlow<CallUiState> = state.asStateFlow()
    private var session: Session? = newSession()
    val viewModel: CallViewModel get() = currentSession().viewModel
    val shouldRestart: Boolean get() = phase == Phase.RUNNING && settingsStore.load().keepConnected

    fun resumePersistedCall() {
        if (restartChecked) return
        restartChecked = true
        if (shouldRestart && state.value.connection == ConnectionState.DISCONNECTED) {
            // onStartCommand itself supplies the start command and sticky result.
            dispatch(CallAction.Connect, refreshStart = false)
        }
    }

    private class Session(
        val dependencies: CallServiceDependencies,
        val scope: CoroutineScope,
        val audio: ForegroundCallAudioEngine,
        val store: ViewModelStore,
        val viewModel: CallViewModel,
        val collector: Job,
    )

    private fun newSession(): Session {
        val id = ++generation
        val dependencies = createDependencies()
        val scope = CoroutineScope(SupervisorJob() + viewModelDispatcher)
        val audio = ForegroundCallAudioEngine(dependencies.audioEngine, canRecord) { active ->
            if (id == generation && (phase == Phase.RUNNING || !active)) onCaptureChanged(active)
        }
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, CallViewModelFactory {
            CallViewModel(dependencies.transport, dependencies.cryptoEngine, audio, settingsStore, scope = scope)
        })[CallViewModel::class.java]
        if (id > 1) {
            // Preserve unsaved UI choices across a stopped-but-bound session.
            vm.dispatch(CallAction.SelectBroker(state.value.broker))
            vm.dispatch(CallAction.UpdateChannel(state.value.channel))
            vm.dispatch(CallAction.UpdateKey(state.value.key))
            if (state.value.microphoneGranted) vm.dispatch(CallAction.RequestMicrophone)
        }
        state.value = vm.uiState.value
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { value ->
                synchronized(stateLock) {
                    if (id == generation && phase != Phase.STOPPING && phase != Phase.DESTROYED) {
                        state.value = value
                    }
                }
            }
        }
        return Session(dependencies, scope, audio, store, vm, collector)
    }

    private fun currentSession(): Session {
        check(phase != Phase.DESTROYED) { "Call service was destroyed" }
        return session ?: newSession().also { session = it }
    }

    fun dispatch(action: CallAction) = dispatch(action, refreshStart = true)

    private fun dispatch(action: CallAction, refreshStart: Boolean) {
        if (phase == Phase.DESTROYED) return
        if (action == CallAction.Disconnect) {
            reconnectPending = false
            stop()
            return
        }
        if (phase == Phase.STOPPING) {
            if (action == CallAction.Connect) reconnectPending = true
            return
        }
        val vm = currentSession().viewModel
        val previousConnection = vm.uiState.value.connection
        if (action == CallAction.Connect && phase == Phase.STOPPED) {
            phase = Phase.RUNNING
        }
        vm.dispatch(action)
        state.value = vm.uiState.value
        if (refreshStart && action == CallAction.Connect &&
            previousConnection != ConnectionState.CONNECTING &&
            previousConnection != ConnectionState.CONNECTED &&
            vm.uiState.value.connection == ConnectionState.CONNECTING &&
            shouldRestart
        ) {
            // Issue a new start command so Android records START_STICKY for this call.
            onStarted()
        }
    }

    fun stop() {
        reconnectPending = false
        if (phase == Phase.DESTROYED || phase == Phase.STOPPING || phase == Phase.STOPPED) return
        settingsStore.setKeepConnected(false)
        session?.viewModel?.dispatch(CallAction.Disconnect)
        phase = Phase.STOPPING
        closeSession(stopService = true)
    }

    fun destroy() {
        if (phase == Phase.DESTROYED) return
        val alreadyStopping = phase == Phase.STOPPING
        phase = Phase.DESTROYED
        reconnectPending = false
        if (!alreadyStopping) closeSession(stopService = false)
    }

    private fun closeSession(stopService: Boolean) {
        val old = session
        old?.collector?.cancel()
        old?.store?.clear() // Immediate capture stop and ViewModel cancellation, without joining.
        synchronized(stateLock) {
            state.value = state.value.copy(
                connection = ConnectionState.DISCONNECTED,
                talkState = TalkState.IDLE,
                talkerName = null,
                bufferState = BufferState.BUFFERING,
                canTalk = false,
                securityLevel = null,
                error = null,
            )
        }
        cleanupScope.launch {
            // Detached workers are deliberate: a blocking driver or NonCancellable operation must
            // not make the timeout itself wait for a child coroutine to finish.
            val workers = CoroutineScope(SupervisorJob() + cleanupDispatcher)
            try {
                if (old != null) {
                    runCatching { old.dependencies.abortTransport() }
                    val jobs = listOf(
                        workers.launch { runCatching { old.audio.stopCapture() } },
                        workers.launch { runCatching { old.audio.stopPlayback() } },
                        workers.launch { runCatching { old.dependencies.transport.disconnect() } },
                        workers.launch { old.scope.coroutineContext[Job]?.join() },
                    )
                    withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { jobs.joinAll() }
                }
            } finally {
                workers.cancel()
                session = null
                if (phase == Phase.DESTROYED) {
                    cleanupScope.cancel()
                } else if (stopService) {
                    phase = Phase.STOPPED
                    onStopped()
                    if (reconnectPending) {
                        reconnectPending = false
                        dispatch(CallAction.Connect)
                    }
                }
            }
        }
    }

    private companion object {
        const val CLEANUP_TIMEOUT_MS = 2_000L
    }
}

/** Promote before touching AudioRecord; release the microphone type after capture stops. */
private class ForegroundCallAudioEngine(
    private val delegate: AudioEngine,
    private val canRecord: () -> Boolean,
    private val onCaptureChanged: suspend (Boolean) -> Unit,
) : AudioEngine by delegate {
    @Volatile private var retired = false

    override fun stopCaptureImmediately() {
        retired = true
        delegate.stopCaptureImmediately()
    }

    override suspend fun startCapture(
        audioPacketIntervalUnits: Int,
        onBatch: suspend (List<ByteArray>) -> Unit,
    ) {
        check(!retired) { "Call session was stopped" }
        check(canRecord()) { "Microphone permission is required. Enable it in App Settings." }
        try {
            onCaptureChanged(true)
            check(!retired) { "Call session was stopped" }
            delegate.startCapture(audioPacketIntervalUnits, onBatch)
        } catch (error: Throwable) {
            withContext(NonCancellable) { onCaptureChanged(false) }
            throw error
        } finally {
            // A driver may finish startup after cancellation or after the cleanup deadline.
            if (retired) delegate.stopCaptureImmediately()
        }
    }

    override suspend fun play(batch: AudioBatch) {
        if (retired) return
        try {
            delegate.play(batch)
        } finally {
            if (retired) withContext(NonCancellable) { delegate.stopPlayback() }
        }
    }

    override suspend fun stopCapture() {
        try {
            delegate.stopCapture()
        } finally {
            withContext(NonCancellable) { onCaptureChanged(false) }
        }
    }
}

internal class CallViewModelFactory(
    private val createViewModel: () -> CallViewModel,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == CallViewModel::class.java)
        @Suppress("UNCHECKED_CAST")
        return createViewModel() as T
    }
}
