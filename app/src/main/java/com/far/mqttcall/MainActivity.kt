package com.far.mqttcall

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.far.mqttcall.floor.TalkState
import com.far.mqttcall.settings.AndroidCallSettingsStore
import com.far.mqttcall.ui.CallScreen
import com.far.mqttcall.ui.MqttCallTheme

class MainActivity : ComponentActivity() {
    private var callBinder by mutableStateOf<CallService.LocalBinder?>(null)
    private var showMicrophoneSettings by mutableStateOf(false)
    private var bindRequested = false
    private var exiting = false
    private val permissionCoordinator by lazy {
        MicrophonePermissionCoordinator(AndroidCallSettingsStore(applicationContext))
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (exiting) return
            callBinder = service as CallService.LocalBinder
            syncMicrophonePermission()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            callBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            val binder = callBinder
            val state = binder?.uiState?.collectAsState()?.value ?: CallUiState()
            val microphoneLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    showMicrophoneSettings = false
                    callBinder?.dispatch(CallAction.RequestMicrophone)
                } else {
                    callBinder?.dispatch(CallAction.MicrophonePermissionRevoked)
                    showMicrophoneSettings = true
                }
            }

            MqttCallTheme {
                CallScreen(
                    state = state,
                    onAction = { action -> callBinder?.dispatch(action) },
                    onPttPress = {
                        when (permissionCoordinator.onPttAttempt(microphoneGranted())) {
                            MicrophoneDecision.PressTalk -> {
                                val bound = callBinder
                                if (bound == null) {
                                    false
                                } else {
                                    showMicrophoneSettings = false
                                    bound.dispatch(CallAction.RequestMicrophone)
                                    if (bound.uiState.value.canTalk) {
                                        bound.dispatch(CallAction.PressTalk)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                            MicrophoneDecision.RequestPermission -> {
                                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                false
                            }
                            MicrophoneDecision.OpenSettings -> {
                                callBinder?.dispatch(CallAction.MicrophonePermissionRevoked)
                                showMicrophoneSettings = true
                                false
                            }
                        }
                    },
                    onExit = ::exitCall,
                    showMicrophoneSettings = showMicrophoneSettings,
                    openAppSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            },
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!exiting) {
            ContextCompat.startForegroundService(this, Intent(this, CallService::class.java))
            bindRequested = bindService(
                Intent(this, CallService::class.java),
                serviceConnection,
                BIND_AUTO_CREATE,
            )
        }
    }

    override fun onStop() {
        if (callBinder?.uiState?.value?.talkState == TalkState.LOCAL_TALKING) {
            callBinder?.dispatch(CallAction.ReleaseTalk)
        }
        unbindCallService()
        super.onStop()
    }

    private fun syncMicrophonePermission() {
        if (microphoneGranted()) {
            callBinder?.dispatch(CallAction.RequestMicrophone)
        } else {
            callBinder?.dispatch(CallAction.MicrophonePermissionRevoked)
        }
    }

    private fun microphoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun unbindCallService() {
        if (bindRequested) {
            unbindService(serviceConnection)
            bindRequested = false
        }
        callBinder = null
    }

    private fun exitCall() {
        if (exiting) return
        exiting = true
        val bound = callBinder
        if (bound != null) {
            bound.dispatch(CallAction.Disconnect)
        } else {
            startService(Intent(this, CallService::class.java).setAction(CallService.ACTION_STOP))
        }
        unbindCallService()
        finishAndRemoveTask()
    }
}
