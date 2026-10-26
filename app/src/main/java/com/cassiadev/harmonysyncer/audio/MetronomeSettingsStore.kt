package com.cassiadev.harmonysyncer.audio

import android.content.Context
import androidx.core.content.edit

/** Keeps the metronome settings across app restarts; a stored value that is unreadable or out of range is dropped. */
class MetronomeSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("metronome", Context.MODE_PRIVATE)

    fun load(): MetronomeSettings {
        val defaults = MetronomeSettings()
        return MetronomeSettings(
            useForRecording = readOrNull { preferences.getBoolean(USE_FOR_RECORDING, defaults.useForRecording) }
                ?: defaults.useForRecording,
            bpm = readOrNull { preferences.getInt(BPM, defaults.bpm) }
                ?.takeIf { it in METRONOME_MIN_BPM..METRONOME_MAX_BPM } ?: defaults.bpm,
            beatsPerBar = readOrNull { preferences.getInt(BEATS_PER_BAR, defaults.beatsPerBar) }
                ?.takeIf { it in METRONOME_BEATS_PER_BAR } ?: defaults.beatsPerBar,
            clickOn = readOrNull { preferences.getBoolean(CLICK_ON, defaults.clickOn) } ?: defaults.clickOn,
        )
    }

    fun save(settings: MetronomeSettings) {
        preferences.edit {
            putBoolean(USE_FOR_RECORDING, settings.useForRecording)
            putInt(BPM, settings.bpm)
            putInt(BEATS_PER_BAR, settings.beatsPerBar)
            putBoolean(CLICK_ON, settings.clickOn)
        }
    }

    // A value stored under the same key with another type throws ClassCastException.
    private fun <T> readOrNull(read: () -> T): T? = try {
        read()
    } catch (_: ClassCastException) {
        null
    }

    private companion object {
        const val USE_FOR_RECORDING = "useForRecording"
        const val BPM = "bpm"
        const val BEATS_PER_BAR = "beatsPerBar"
        const val CLICK_ON = "clickOn"
    }
}
