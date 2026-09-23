package com.far.mqttcall

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
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
import com.far.mqttcall.ui.CallScreen
import com.far.mqttcall.ui.MqttCallTheme

class MainActivity : ComponentActivity() {
    private var callBinder by mutableStateOf<CallService.LocalBinder?>(null)
    private var bindRequested = false
    private var exiting = false

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
                    callBinder?.dispatch(CallAction.RequestMicrophone)
                } else {
                    callBinder?.dispatch(CallAction.MicrophonePermissionRevoked)
                }
            }

            MqttCallTheme {
                CallScreen(
                    state = state,
                    onAction = { action -> callBinder?.dispatch(action) },
                    requestMicrophone = {
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            callBinder?.dispatch(CallAction.RequestMicrophone)
        } else {
            callBinder?.dispatch(CallAction.MicrophonePermissionRevoked)
        }
    }

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
        callBinder?.dispatch(CallAction.Disconnect)
        unbindCallService()
        finishAndRemoveTask()
    }
}
