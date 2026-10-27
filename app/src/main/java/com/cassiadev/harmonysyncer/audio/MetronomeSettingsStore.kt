package com.cassiadev.harmonysyncer.audio

import android.content.Context
import androidx.core.content.edit

/** Keeps the metronome settings across app restarts; a stored value that is unreadable or out of range is dropped. */
class MetronomeSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("metronome", Context.MODE_PRIVATE)

    fun load(): MetronomeSettings {
        val defaults = MetronomeSettings()
        // Read by type from the stored map: getInt and getBoolean throw on a value stored with another type.
        val stored = preferences.all
        return MetronomeSettings(
            useForRecording = stored[USE_FOR_RECORDING] as? Boolean ?: defaults.useForRecording,
            bpm = (stored[BPM] as? Int)?.takeIf { it in METRONOME_MIN_BPM..METRONOME_MAX_BPM } ?: defaults.bpm,
            beatsPerBar = (stored[BEATS_PER_BAR] as? Int)?.takeIf { it in METRONOME_BEATS_PER_BAR } ?: defaults.beatsPerBar,
            clickOn = stored[CLICK_ON] as? Boolean ?: defaults.clickOn,
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

    private companion object {
        const val USE_FOR_RECORDING = "useForRecording"
        const val BPM = "bpm"
        const val BEATS_PER_BAR = "beatsPerBar"
        const val CLICK_ON = "clickOn"
    }
}
