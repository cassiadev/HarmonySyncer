package com.cassiadev.harmonysyncer

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cassiadev.harmonysyncer.audio.AudioPreviewController

/** Keeps loaded files and settings across configuration changes; leaving the screen closes them. */
class AudioPreviewViewModel(val controller: AudioPreviewController) : ViewModel() {
    override fun onCleared() = controller.close()

    companion object {
        /** Device tests replace this before launching the activity, because the first ViewModel keeps it. */
        @VisibleForTesting
        internal var createController: (Context) -> AudioPreviewController = ::AudioPreviewController

        val Factory = viewModelFactory {
            initializer { AudioPreviewViewModel(createController(requireNotNull(this[APPLICATION_KEY]))) }
        }
    }
}
