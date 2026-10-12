package com.cassiadev.harmonysyncer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cassiadev.harmonysyncer.audio.AudioPreviewController
import com.cassiadev.harmonysyncer.audio.TrackSlot
import com.cassiadev.harmonysyncer.ui.AudioPreviewScreen
import com.cassiadev.harmonysyncer.ui.theme.HarmonySyncerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember { AudioPreviewController(applicationContext) }
            val pickerA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { controller.selectFile(TrackSlot.A, it) }
            }
            val pickerB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { controller.selectFile(TrackSlot.B, it) }
            }

            DisposableEffect(controller, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) controller.stop()
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    controller.close()
                }
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
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
