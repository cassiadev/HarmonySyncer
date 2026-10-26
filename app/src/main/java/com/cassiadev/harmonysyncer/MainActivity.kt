package com.cassiadev.harmonysyncer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.cassiadev.harmonysyncer.audio.TrackSlot
import com.cassiadev.harmonysyncer.ui.AudioPreviewScreen
import com.cassiadev.harmonysyncer.ui.theme.HarmonySyncerTheme

class MainActivity : ComponentActivity() {
    private val previewModel: AudioPreviewViewModel by viewModels { AudioPreviewViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // TalkBack announces the window title on open; there is no title bar, so only speech changes.
        title = getString(R.string.app_name_spoken)
        val controller = previewModel.controller
        val canRecord = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        setContent {
            val pickerA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { controller.selectFile(TrackSlot.A, it) }
            }
            val pickerB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { controller.selectFile(TrackSlot.B, it) }
            }
            // Saved, so a recreation while the permission dialog is open still starts the right slot.
            var pendingRecordSlot by rememberSaveable { mutableStateOf<TrackSlot?>(null) }
            val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val slot = pendingRecordSlot ?: return@rememberLauncherForActivityResult
                pendingRecordSlot = null
                if (granted) controller.startRecording(slot) else controller.reportMicrophoneDenied()
            }

            HarmonySyncerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AudioPreviewScreen(
                        state = controller.state,
                        onSelectA = { pickerA.launch(arrayOf("audio/*")) },
                        onSelectB = { pickerB.launch(arrayOf("audio/*")) },
                        onSettingsChange = controller::updateSettings,
                        onPlay = controller::play,
                        onStop = controller::stop,
                        onLoadSample = controller::loadSample,
                        modifier = Modifier.padding(innerPadding),
                        canRecord = canRecord,
                        onRecord = { slot ->
                            val permission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                            if (permission == PackageManager.PERMISSION_GRANTED) {
                                controller.startRecording(slot)
                            } else {
                                pendingRecordSlot = slot
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onStopRecording = controller::stopRecording,
                    )
                }
            }
        }
    }

    // Also retries saves that failed earlier, when the user comes back to the app.
    override fun onStart() {
        super.onStart()
        previewModel.controller.recoverDrafts()
    }

    // Playback stops in the background and during recreation; loaded files stay in the ViewModel.
    // A recording keeps going through recreation and is stopped and saved only when the app leaves the screen.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) previewModel.controller.interruptRecording()
        previewModel.controller.stop()
    }
}
