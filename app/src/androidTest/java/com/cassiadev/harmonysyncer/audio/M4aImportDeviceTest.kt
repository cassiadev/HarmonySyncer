package com.cassiadev.harmonysyncer.audio

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real AAC fixtures exercise the document loader, Android decoder and mono mix. */
class M4aImportDeviceTest {
    @Test
    fun monoM4aRetainsItsDurationAndSignal() = runBlocking {
        withFixture("mono-44100.m4a") { file ->
            val loaded = loader().load(Uri.fromFile(file))
            assertEquals(file.name, loaded.name)
            assertEquals(44_100, loaded.clip.sampleRate)
            assertEquals(44_100, loaded.clip.samples.size)
            assertTrue("Decoded tone is missing", toneAmplitude(loaded.clip, 1_000) > 10_000)
        }
    }

    @Test
    fun stereoM4aCombinesBothChannelsAtHalfGain() = runBlocking {
        withFixture("stereo-48000.m4a") { file ->
            val clip = loader().load(Uri.fromFile(file)).clip
            assertEquals(48_000, clip.sampleRate)
            assertEquals(48_000, clip.samples.size)
            // Left contains a 12,000-amplitude 1 kHz tone; right a 6,000-amplitude 3 kHz tone.
            assertEquals(6_000.0, toneAmplitude(clip, 1_000), 600.0)
            assertEquals(3_000.0, toneAmplitude(clip, 3_000), 400.0)
        }
    }

    @Test
    fun m4aHeaderIsRecognizedWithoutAFileExtension() = runBlocking {
        withFixture("mono-44100.m4a", ".bin") { file ->
            assertEquals(44_100, loader().load(Uri.fromFile(file)).clip.samples.size)
        }
    }

    @Test
    fun exactlySixtySecondsOfM4aIsAccepted() = runBlocking {
        withFixture("sixty-seconds.m4a") { file ->
            val clip = loader().load(Uri.fromFile(file)).clip
            assertEquals(48_000, clip.sampleRate)
            assertEquals(2_880_000, clip.samples.size)
        }
    }

    @Test
    fun m4aLongerThanSixtySecondsIsRejected() = runBlocking {
        withFixture("over-sixty-seconds.m4a") { file ->
            val failure = runCatching { loader().load(Uri.fromFile(file)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("60초"))
        }
    }

    @Test
    fun unsupportedM4aEncodingExplainsSupportedFormat() = runBlocking {
        withFixture("unsupported-alac.m4a") { file ->
            val failure = runCatching { loader().load(Uri.fromFile(file)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("AAC"))
        }
    }

    @Test
    fun truncatedM4aIsRejectedAndTheNextImportStillWorks() = runBlocking {
        withFixture("mono-44100.m4a") { file ->
            val valid = file.readBytes()
            file.writeBytes(valid.copyOf(100))
            assertTrue(runCatching { loader().load(Uri.fromFile(file)) }.exceptionOrNull() is IllegalArgumentException)
            file.writeBytes(valid)
            assertEquals(44_100, loader().load(Uri.fromFile(file)).clip.samples.size)
        }
    }

    @Test
    fun truncatedAudioPayloadIsRejectedInsteadOfImportingItsPrefix() = runBlocking {
        withFixture("mono-44100.m4a") { file ->
            val valid = file.readBytes()
            // Keep the complete metadata but remove the end of the declared audio-data box.
            file.writeBytes(valid.copyOf(6_000))
            val failure = runCatching { loader().load(Uri.fromFile(file)) }.exceptionOrNull()
            assertTrue("A truncated recording was imported as a shorter clip", failure is IllegalArgumentException)
            file.writeBytes(valid)
            assertEquals(44_100, loader().load(Uri.fromFile(file)).clip.samples.size)
        }
    }

    @Test
    fun m4aLargerThanFileLimitIsRejectedBeforeDecoding() = runBlocking {
        withFixture("mono-44100.m4a") { file ->
            RandomAccessFile(file, "rw").use { it.setLength(32L * 1_024 * 1_024 + 1) }
            val failure = runCatching { loader().load(Uri.fromFile(file)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("32 MiB"))
        }
    }

    private fun loader() = AudioFileLoader(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver)

    private suspend fun withFixture(name: String, suffix: String = ".m4a", block: suspend (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("import-", suffix, instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("audio/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun toneAmplitude(clip: PcmClip, frequency: Int): Double {
        val start = clip.sampleRate / 10
        val end = clip.sampleRate * 9 / 10
        return abs((start until end).sumOf {
            clip.samples[it] * sin(2 * PI * frequency * it / clip.sampleRate)
        } * 2 / (end - start))
    }
}
