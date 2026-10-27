package com.cassiadev.harmonysyncer.audio

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MetronomeSettingsStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("metronome", Context.MODE_PRIVATE)

    @Before
    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun savedSettingsLoadBack() {
        val settings = MetronomeSettings(useForRecording = true, bpm = 97, beatsPerBar = 3, clickOn = false)
        MetronomeSettingsStore(context).save(settings)

        assertEquals(settings, MetronomeSettingsStore(context).load())
    }

    @Test
    fun missingSettingsUseDefaults() {
        assertEquals(MetronomeSettings(), MetronomeSettingsStore(context).load())
    }

    @Test
    fun invalidValuesFallBackPerKey() {
        preferences.edit().putInt("bpm", 999).putInt("beatsPerBar", 5).putBoolean("clickOn", false).commit()

        assertEquals(MetronomeSettings(clickOn = false), MetronomeSettingsStore(context).load())
    }

    @Test
    fun wrongTypeFallsBack() {
        preferences.edit().putString("bpm", "fast").putInt("beatsPerBar", 3).commit()

        assertEquals(MetronomeSettings(beatsPerBar = 3), MetronomeSettingsStore(context).load())
    }
}
