package com.cassiadev.harmonysyncer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPreviewTest {
    @Test
    fun `positive offset delays B with silence and preserves both ends`() {
        val result = renderPreview(
            clip(100, 200, 300),
            clip(400, 500, 600, 700),
            MixSettings(offsetMs = 2),
        )

        assertEquals(1_000, result.sampleRate)
        assertArrayEquals(shortArrayOf(50, 100, 350, 250, 300, 350), result.samples)
    }

    @Test
    fun `negative offset delays A so B starts earlier without discarding samples`() {
        val result = renderPreview(
            clip(100, 200, 300, 400),
            clip(600, 800, 1_000),
            MixSettings(offsetMs = -2),
        )

        assertArrayEquals(shortArrayOf(300, 400, 550, 100, 150, 200), result.samples)
    }

    @Test
    fun `zero offset matches the existing mixer and preserves its rounding`() {
        val a = clip(1, -1, 3, -3, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        val b = clip(0)

        val result = renderPreview(a, b, MixSettings())

        assertArrayEquals(mixPcm16(a.samples, b.samples), result.samples)
        assertArrayEquals(shortArrayOf(1, 0, 2, -1, 16_384, -16_384), result.samples)
    }

    @Test
    fun `milliseconds use the clip sample rate and nearest-frame rounding`() {
        val a = PcmClip(shortArrayOf(0), 44_100)
        val b = PcmClip(shortArrayOf(2_000), 44_100)

        val oneMillisecond = renderPreview(a, b, MixSettings(offsetMs = 1))
        val fiveMilliseconds = renderPreview(a, b, MixSettings(offsetMs = 5))
        val negativeFive = renderPreview(b, a, MixSettings(offsetMs = -5))

        assertEquals(45, oneMillisecond.samples.size)
        assertEquals(1_000.toShort(), oneMillisecond.samples[44])
        assertEquals(222, fiveMilliseconds.samples.size)
        assertEquals(1_000.toShort(), fiveMilliseconds.samples[221])
        assertArrayEquals(fiveMilliseconds.samples, negativeFive.samples)
    }

    @Test
    fun `one millisecond is forty eight frames at 48 kHz`() {
        val result = renderPreview(
            PcmClip(shortArrayOf(2_000), 48_000),
            PcmClip(shortArrayOf(4_000), 48_000),
            MixSettings(offsetMs = 1),
        )

        assertEquals(49, result.samples.size)
        assertEquals(1_000.toShort(), result.samples[0])
        assertEquals(2_000.toShort(), result.samples[48])
        assertArrayEquals(ShortArray(47), result.samples.copyOfRange(1, 48))
    }

    @Test
    fun `volumes apply independently with existing half gain headroom`() {
        val a = clip(10_000, -10_000)
        val b = clip(6_000, -6_000)

        assertArrayEquals(shortArrayOf(3_000, -3_000), renderPreview(a, b, MixSettings(volumeA = 0f)).samples)
        assertArrayEquals(shortArrayOf(5_000, -5_000), renderPreview(a, b, MixSettings(volumeB = 0f)).samples)
        assertArrayEquals(shortArrayOf(0, 0), renderPreview(a, b, MixSettings(0f, 0f)).samples)
        assertArrayEquals(shortArrayOf(4_000, -4_000), renderPreview(a, b, MixSettings(0.5f, 0.5f)).samples)
    }

    @Test
    fun `rendering and preparing do not modify or reuse the source buffers`() {
        val a = clip(100, 200, 300)
        val b = clip(400, 500, 600, 700)
        val originalA = a.samples.copyOf()
        val originalB = b.samples.copyOf()

        val rendered = renderPreview(a, b, MixSettings(offsetMs = -2))
        val prepared = preparePreview(a, b, MixSettings(offsetMs = 2), onsetA = 1, onsetB = 2)

        assertNotSame(a.samples, rendered.samples)
        assertNotSame(b.samples, rendered.samples)
        assertNotSame(a.samples, prepared.samples)
        assertNotSame(b.samples, prepared.samples)
        rendered.samples.fill(0)
        prepared.samples.fill(0)
        assertArrayEquals(originalA, a.samples)
        assertArrayEquals(originalB, b.samples)
    }

    @Test
    fun `rejects empty clips and nonpositive rates`() {
        val valid = clip(100)
        val invalid = listOf(
            PcmClip(shortArrayOf(), 1_000),
            PcmClip(shortArrayOf(100), 0),
            PcmClip(shortArrayOf(100), -1),
        )

        for (badClip in invalid) {
            assertThrows(IllegalArgumentException::class.java) { renderPreview(badClip, valid, MixSettings()) }
            assertThrows(IllegalArgumentException::class.java) { renderPreview(valid, badClip, MixSettings()) }
            assertThrows(IllegalArgumentException::class.java) { preparePreview(badClip, valid, MixSettings()) }
            assertThrows(IllegalArgumentException::class.java) { preparePreview(valid, badClip, MixSettings()) }
        }
    }

    @Test
    fun `rejects unsupported rates before offset allocation or onset alignment`() {
        for (rate in intArrayOf(PCM_MAX_SAMPLE_RATE + 1, Int.MAX_VALUE)) {
            val a = PcmClip(shortArrayOf(100), rate)
            val b = PcmClip(shortArrayOf(200), rate)
            for (offset in intArrayOf(-300, 300)) {
                val renderedFailure = assertThrows(IllegalArgumentException::class.java) {
                    renderPreview(a, b, MixSettings(offsetMs = offset))
                }
                val preparedFailure = assertThrows(IllegalArgumentException::class.java) {
                    preparePreview(a, b, MixSettings(offsetMs = offset), onsetA = -1, onsetB = 10)
                }
                assertEquals("음원의 샘플레이트는 1~192,000Hz여야 합니다.", renderedFailure.message)
                assertEquals(renderedFailure.message, preparedFailure.message)
            }
        }
    }

    @Test
    fun `accepts the maximum supported sample rate`() {
        val result = renderPreview(
            PcmClip(shortArrayOf(2_000), PCM_MAX_SAMPLE_RATE),
            PcmClip(shortArrayOf(4_000), PCM_MAX_SAMPLE_RATE),
            MixSettings(offsetMs = 300),
        )

        assertEquals(PCM_MAX_SAMPLE_RATE, result.sampleRate)
        assertEquals(57_601, result.samples.size)
        assertEquals(1_000.toShort(), result.samples.first())
        assertEquals(2_000.toShort(), result.samples.last())
    }

    @Test
    fun `rejects invalid volumes on either track`() {
        val invalid = floatArrayOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (volume in invalid) {
            for (settings in listOf(MixSettings(volumeA = volume), MixSettings(volumeB = volume))) {
                assertThrows(IllegalArgumentException::class.java) {
                    renderPreview(clip(100), clip(200), settings)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    preparePreview(clip(100), clip(200), settings)
                }
            }
        }
    }

    @Test
    fun `accepts the offset limits and rejects offsets outside them`() {
        for (offset in intArrayOf(-300, 300)) {
            val result = renderPreview(clip(100), clip(200), MixSettings(offsetMs = offset))
            assertEquals(301, result.samples.size)
        }
        for (offset in intArrayOf(-301, 301, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                renderPreview(clip(100), clip(200), MixSettings(offsetMs = offset))
            }
            assertThrows(IllegalArgumentException::class.java) {
                preparePreview(clip(100), clip(200), MixSettings(offsetMs = offset))
            }
        }
    }

    @Test
    fun `supplied onset correction happens before the manual adjustment exactly once`() {
        val a = clip(9_000, 8_000, 100, 200, 300)
        val b = clip(7_000, 6_000, 5_000, 400, 500, 600, 700)

        val result = preparePreview(a, b, MixSettings(offsetMs = 2), onsetA = 2, onsetB = 3)

        assertArrayEquals(shortArrayOf(50, 100, 350, 250, 300, 350), result.samples)
    }

    @Test
    fun `default zero onsets retain the original leading silence`() {
        val a = clip(0, 0, 2_000)
        val b = clip(0, 4_000)

        val result = preparePreview(a, b, MixSettings())

        assertArrayEquals(shortArrayOf(0, 2_000, 1_000), result.samples)
    }

    @Test
    fun `different rates preserve duration pitch and source samples when mixed`() {
        val source = ShortArray(44_100) { (12_000 * sin(2 * PI * 1_000 * it / 44_100)).roundToInt().toShort() }
        val original = source.copyOf()
        val result = preparePreview(
            PcmClip(source, 44_100),
            PcmClip(ShortArray(48_000), 48_000),
            MixSettings(),
        )

        assertEquals(48_000, result.sampleRate)
        assertEquals(48_000, result.samples.size)
        // Compare against the independently generated tone, avoiding filter edge transients.
        val meanError = (100 until 47_900).sumOf {
            abs(result.samples[it] - 6_000 * sin(2 * PI * 1_000 * it / 48_000))
        } / 47_800
        assertTrue("Resampling changed the tone: mean error $meanError", meanError < 10)
        assertArrayEquals(original, source)
    }

    @Test
    fun `different rates apply volumes and offset at the shared rate`() {
        val result = renderPreview(
            PcmClip(ShortArray(100) { 8_000 }, 1_000),
            PcmClip(ShortArray(200) { 4_000 }, 2_000),
            MixSettings(volumeA = 0.5f, volumeB = 0.25f, offsetMs = 25),
        )

        assertEquals(2_000, result.sampleRate)
        assertEquals(250, result.samples.size)
        assertArrayEquals(ShortArray(50) { 2_000 }, result.samples.copyOfRange(0, 50))
        assertArrayEquals(ShortArray(150) { 2_500 }, result.samples.copyOfRange(50, 200))
        assertArrayEquals(ShortArray(50) { 500 }, result.samples.copyOfRange(200, 250))
    }

    @Test
    fun `onsets are measured in each original rate before resampling`() {
        val result = preparePreview(
            PcmClip(ShortArray(150) { 4_000 }, 1_000),
            PcmClip(ShortArray(300) { 8_000 }, 2_000),
            MixSettings(),
            onsetA = 100,
            onsetB = 200,
        )

        assertEquals(2_000, result.sampleRate)
        assertArrayEquals(ShortArray(100) { 6_000 }, result.samples)
    }

    @Test
    fun `different rates still reject onsets outside original clips`() {
        for (onset in intArrayOf(-1, 2)) {
            assertThrows(IllegalArgumentException::class.java) {
                preparePreview(
                    PcmClip(shortArrayOf(100, 200), 24_000),
                    PcmClip(shortArrayOf(300, 400), 48_000),
                    MixSettings(),
                    onsetA = onset,
                )
            }
        }
    }

    @Test
    fun `prepare rejects onsets outside either clip`() {
        for (onset in intArrayOf(-1, 2)) {
            assertThrows(IllegalArgumentException::class.java) {
                preparePreview(clip(100, 200), clip(300, 400), MixSettings(), onsetA = onset)
            }
            assertThrows(IllegalArgumentException::class.java) {
                preparePreview(clip(100, 200), clip(300, 400), MixSettings(), onsetB = onset)
            }
        }
    }

    private fun clip(vararg samples: Int): PcmClip =
        PcmClip(ShortArray(samples.size) { samples[it].toShort() }, 1_000)
}
