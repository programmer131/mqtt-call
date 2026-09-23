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
import com.far.mqttcall.audio.AudioEngine
import com.far.mqttcall.crypto.AndroidCryptoEngine
import com.far.mqttcall.crypto.CryptoEngine
import com.far.mqttcall.settings.AndroidCallSettingsStore
import com.far.mqttcall.settings.CallSettingsStore
import com.far.mqttcall.transport.MqttTransport
import com.far.mqttcall.transport.PahoMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var owner: CallServiceLifecycle
    private val binder = LocalBinder()
    private var microphoneActive = false
    private var destroyed = false

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
            transport = PahoMqttTransport(),
            cryptoEngine = AndroidCryptoEngine(applicationContext),
            audioEngine = AndroidAudioEngine(applicationContext),
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
                serviceScope.cancel()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
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

/** Service-owned resources, separated from Android callbacks for JVM lifecycle tests. */
internal class CallServiceLifecycle(
    private val transport: MqttTransport,
    cryptoEngine: CryptoEngine,
    audioEngine: AudioEngine,
    private val settingsStore: CallSettingsStore,
    canRecord: () -> Boolean,
    onCaptureChanged: suspend (Boolean) -> Unit,
    private val onStopped: () -> Unit,
    private val viewModelScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val audio = ForegroundCallAudioEngine(audioEngine, canRecord, onCaptureChanged)
    private val store = ViewModelStore()
    private var closed = false
    val viewModel: CallViewModel = ViewModelProvider.create(
        store,
        CallViewModelFactory {
            CallViewModel(transport, cryptoEngine, audio, settingsStore, scope = viewModelScope)
        },
    )[CallViewModel::class.java]
    val uiState: StateFlow<CallUiState> get() = viewModel.uiState
    val shouldRestart: Boolean get() = !closed && settingsStore.load().keepConnected

    fun dispatch(action: CallAction) {
        if (closed) return
        if (action == CallAction.Disconnect) stop() else viewModel.dispatch(action)
    }

    fun stop() {
        if (closed) return
        settingsStore.setKeepConnected(false)
        viewModel.dispatch(CallAction.Disconnect)
        close(stopService = true)
    }

    fun destroy() = close(stopService = false)

    private fun close(stopService: Boolean) {
        if (closed) return
        closed = true
        // Clearing cancels the ViewModel's work and invokes immediate capture stop synchronously.
        store.clear()
        // This scope outlives the ViewModel and the notification collector during onDestroy.
        cleanupScope.launch {
            try {
                // Cancellation alone does not wait for an in-flight recorder/transport startup.
                viewModelScope.coroutineContext[Job]?.join()
                runCatching { audio.stopCapture() }
                runCatching { audio.stopPlayback() }
                runCatching { transport.disconnect() }
            } finally {
                if (stopService) onStopped()
                cleanupScope.cancel()
            }
        }
    }
}

/** Promote before touching AudioRecord; release the microphone type after capture stops. */
private class ForegroundCallAudioEngine(
    private val delegate: AudioEngine,
    private val canRecord: () -> Boolean,
    private val onCaptureChanged: suspend (Boolean) -> Unit,
) : AudioEngine by delegate {
    override suspend fun startCapture(
        audioPacketIntervalUnits: Int,
        onBatch: suspend (List<ByteArray>) -> Unit,
    ) {
        check(canRecord()) { "Microphone permission is required. Enable it in App Settings." }
        try {
            onCaptureChanged(true)
            delegate.startCapture(audioPacketIntervalUnits, onBatch)
        } catch (error: Throwable) {
            withContext(NonCancellable) { onCaptureChanged(false) }
            throw error
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
    // Temporary Activity bridge until Task 4 switches the UI to LocalBinder.
    constructor(context: Context) : this({
        CallViewModel(
            transport = PahoMqttTransport(),
            cryptoEngine = AndroidCryptoEngine(context),
            audioEngine = AndroidAudioEngine(context),
            settingsStore = AndroidCallSettingsStore(context),
        )
    })

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == CallViewModel::class.java)
        @Suppress("UNCHECKED_CAST")
        return createViewModel() as T
    }
}
