package com.cassiadev.harmonysyncer.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays through the real output; run with earphones and Bluetooth audio disconnected. */
class ClickTrackPlayerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reportsCountInStartWithinASecond() {
        val player = ClickTrackPlayer()
        val begin = System.nanoTime()
        var countIn: Long? = null
        var reportedAt = 0L
        var measured = false
        player.play(
            MetronomeSettings(clickOn = true),
            recording = false,
            isArmed = { true },
            shouldStop = { reportedAt != 0L && System.nanoTime() - reportedAt > 2_000_000_000L },
        ) { nanos, isMeasured ->
            countIn = nanos
            measured = isMeasured
            reportedAt = System.nanoTime()
        }

        assertTrue("reported after ${(reportedAt - begin) / 1_000_000} ms", reportedAt - begin < 1_000_000_000L)
        assertTrue("timestamp was not measured", measured)
        val start = checkNotNull(countIn) { "no count-in reported" }
        assertTrue("count-in ${start - reportedAt} ns after the report", start > reportedAt)
    }

    @Test
    fun builtInSpeakerMutesRecordingClicks() {
        val outputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        assertTrue(
            "이어폰을 빼고 실행: ${outputs.map { it.type }}",
            outputs.none { allowsRecordingClick(it.type) },
        )
        val player = ClickTrackPlayer()
        val begin = System.nanoTime()
        player.play(
            MetronomeSettings(clickOn = true),
            recording = true,
            isArmed = { true },
            shouldStop = { System.nanoTime() - begin > 1_000_000_000L },
        ) { _, _ -> }

        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, player.lastRoutedDeviceType)
        assertTrue(player.lastChunkMuted)
    }
}
