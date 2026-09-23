package com.far.mqttcall

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.far.mqttcall.ui.CallScreen
import com.far.mqttcall.ui.MqttCallTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Playback uses the media stream, so hardware volume keys should adjust it.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            val callViewModel: CallViewModel = viewModel(
                factory = CallViewModelFactory(applicationContext),
            )
            val state by callViewModel.uiState.collectAsState()
            val microphoneLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) callViewModel.dispatch(CallAction.RequestMicrophone)
            }
            val microphoneGranted = remember {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            }

            LaunchedEffect(microphoneGranted) {
                if (microphoneGranted) callViewModel.dispatch(CallAction.RequestMicrophone)
            }

            MqttCallTheme {
                CallScreen(
                    state = state,
                    onAction = callViewModel::dispatch,
                    requestMicrophone = {
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }
}
