package com.cassiadev.harmonysyncer

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
import androidx.compose.ui.Modifier
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
        setContent {
            val pickerA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { controller.selectFile(TrackSlot.A, it) }
            }
            val pickerB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { controller.selectFile(TrackSlot.B, it) }
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
                    )
                }
            }
        }
    }

    // Playback stops in the background and during recreation; loaded files stay in the ViewModel.
    override fun onStop() {
        super.onStop()
        previewModel.controller.stop()
    }
}
