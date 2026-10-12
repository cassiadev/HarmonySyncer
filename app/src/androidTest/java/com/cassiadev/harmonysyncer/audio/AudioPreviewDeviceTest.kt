package com.cassiadev.harmonysyncer.audio

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPreviewDeviceTest {
    @Test
    fun uriLoaderReadsRealWavAndUsesDisplayNameFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = shortArrayOf(2_000, -4_000, Short.MIN_VALUE, Short.MAX_VALUE)
        val file = File.createTempFile("preview-", ".wav", context.cacheDir)
        try {
            file.writeBytes(wavBytes(samples, 44_100))
            val loaded = AudioFileLoader(context.contentResolver).load(Uri.fromFile(file))
            assertEquals(file.name, loaded.name)
            assertEquals(44_100, loaded.clip.sampleRate)
            assertArrayEquals(samples, loaded.clip.samples)
        } finally {
            file.delete()
        }
    }

    @Test
    fun staticPlaybackWaitsForFinalFrameAndCanReplay() = runBlocking {
        val player = PcmPreviewPlayer()
        try {
            // Silence verifies native playback without emitting test tones.
            val preview = PcmClip(ShortArray(4_800), 48_000)
            val elapsed = measureTimeMillis { withTimeout(5_000) { player.play(preview) } }
            assertTrue("Playback returned before the 100 ms buffer finished", elapsed >= 70)
            withTimeout(5_000) { player.play(preview) }
        } finally {
            player.close()
        }
    }

    @Test
    fun stopEndsNativePlaybackAndNextPlayStartsNewSession() = runBlocking {
        val player = PcmPreviewPlayer()
        try {
            withTimeout(5_000) {
                val playing = launch { player.play(PcmClip(ShortArray(48_000 * 3), 48_000)) }
                delay(250)
                val elapsed = measureTimeMillis {
                    player.stop()
                    playing.join()
                }
                assertTrue("Stop did not cancel native playback promptly", elapsed < 1_000)
                assertTrue(playing.isCancelled)
                player.play(PcmClip(ShortArray(2_400), 48_000))
            }
        } finally {
            player.close()
        }
    }

    private fun wavBytes(samples: ShortArray, rate: Int): ByteArray {
        val output = ByteArrayOutputStream()
        fun number(value: Int, bytes: Int) {
            repeat(bytes) { output.write(value ushr (it * 8) and 0xff) }
        }
        fun id(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        id("RIFF"); number(36 + samples.size * 2, 4); id("WAVE")
        id("fmt "); number(16, 4); number(1, 2); number(1, 2)
        number(rate, 4); number(rate * 2, 4); number(2, 2); number(16, 2)
        id("data"); number(samples.size * 2, 4)
        samples.forEach { number(it.toInt(), 2) }
        return output.toByteArray()
    }
}
