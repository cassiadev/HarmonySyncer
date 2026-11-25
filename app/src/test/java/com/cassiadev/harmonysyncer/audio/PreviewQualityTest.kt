package com.cassiadev.harmonysyncer.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures the whole mixing path against signals with known answers: where offsets land, how long resampled
 * clips are, how tones and full-scale signals come through, and whether timing holds over 60 seconds.
 */
class PreviewQualityTest {
    private val renderPaths: List<Pair<String, (PcmClip, PcmClip, MixSettings) -> PcmClip>> = listOf(
        "renderPreview" to { a, b, settings -> renderPreview(a, b, settings) },
        "preparePreview" to { a, b, settings -> preparePreview(a, b, settings) },
    )

    @Test
    fun `start offsets land on the rounded frame at every millisecond`() {
        for ((rateA, rateB) in listOf(44_100 to 44_100, 48_000 to 48_000, 44_100 to 48_000)) {
            val rate = maxOf(rateA, rateB)
            val a = PcmClip(shortArrayOf(2_000), rateA)
            val b = PcmClip(shortArrayOf(4_000), rateB)
            for ((path, render) in renderPaths) {
                for (ms in -300..300) {
                    val frames = ((abs(ms) * rate * 2L + 1_000) / 2_000).toInt()
                    val startA = if (ms < 0) frames else 0
                    val startB = if (ms > 0) frames else 0
                    val result = render(a, b, MixSettings(offsetMs = ms)).samples
                    val label = "$path $rateA+$rateB at $ms ms"
                    assertEquals(label, maxOf(startA, startB) + 1, result.size)
                    if (ms == 0) {
                        assertEquals(label, 3_000.toShort(), result[0])
                    } else {
                        assertEquals(label, 1_000.toShort(), result[startA])
                        assertEquals(label, 2_000.toShort(), result[startB])
                    }
                }
            }
        }
    }

    @Test
    fun `resampled lengths stay within half a frame of the exact length`() {
        for (frames in listOf(1, 2, 147, 1_000, 44_099, 44_100, 44_101)) {
            val output = resamplePcmUp(PcmClip(ShortArray(frames), 44_100), 48_000) {}.samples.size
            assertTrue("$frames frames became $output", abs(output * 44_100L - frames * 48_000L) * 2 <= 44_100)
        }
    }

    @Test
    fun `two full scale voices at one rate survive the mix unchanged`() {
        val rate = 48_000
        val full = ShortArray(rate / 4) { if (it % 48 < 24) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort() }

        assertArrayEquals(full, renderPreview(PcmClip(full, rate), PcmClip(full, rate), MixSettings()).samples)
    }

    @Test
    fun `tones keep their level through resampling`() {
        for (frequency in listOf(100, 1_000, 5_000, 10_000, 15_000, 18_000, 19_000, 20_000, 21_000, 22_000)) {
            val tone = buildTone(frequency, SOURCE_RATE / 4)
            val output = resamplePcmUp(PcmClip(tone, SOURCE_RATE), TARGET_RATE) {}.samples
            val fit = fitTone(output, 1_200, FIT_FRAMES, frequency, TARGET_RATE)
            val gainDb = 20 * log10(fit.amplitude / TONE_AMPLITUDE)
            report("tone $frequency Hz: gain %.3f dB, other content %.1f dB".format(gainDb, fit.residualDb))
            if (frequency <= 19_000) assertEquals("gain at $frequency Hz", 0.0, gainDb, MAX_GAIN_ERROR_DB)
            if (frequency <= 18_000) assertTrue("other content at $frequency Hz: ${fit.residualDb}", fit.residualDb <= MAX_OTHER_CONTENT_DB)
        }
    }

    /**
     * Phase repeats every cycle, so this sees only drift within one cycle. PreviewLongFileTest pins the absolute
     * position with a click every second; a 48-frame shift left this reading unchanged and failed that test.
     */
    @Test
    fun `a sixty second tone keeps its timing`() {
        for (frequency in listOf(1_000, 5_000)) {
            val tone = buildTone(frequency, 60 * SOURCE_RATE)
            val output = resamplePcmUp(PcmClip(tone, SOURCE_RATE), TARGET_RATE) {}.samples
            assertEquals(2_880_000, output.size)
            for (from in listOf(48_000, 1_440_000, 2_822_400)) {
                val fit = fitTone(output, from, FIT_FRAMES, frequency, TARGET_RATE)
                report("60 s tone $frequency Hz at frame $from: delay %.5f frames".format(fit.delayFrames))
                assertEquals("delay at frame $from", 0.0, fit.delayFrames, MAX_DELAY_FRAMES)
            }
        }
    }

    /** The peaks are estimates from a scaled-down conversion; the mix error compares them with the real path. */
    @Test
    fun `overshoot shows up against the unclipped reference`() {
        val random = Random(1)
        val frames = SOURCE_RATE / 4
        val signals = linkedMapOf(
            "1 kHz sine" to ShortArray(frames) { (Short.MAX_VALUE * sin(2 * PI * 1_000 * it / SOURCE_RATE)).roundToInt().toShort() },
            "1 kHz square" to ShortArray(frames) { if (sin(2 * PI * 1_000 * it / SOURCE_RATE) >= 0) Short.MAX_VALUE else Short.MIN_VALUE },
            "noise" to ShortArray(frames) { (random.nextInt(65_536) + Short.MIN_VALUE).toShort() },
            "quarter rate" to ShortArray(frames) { if (it % 4 < 2) Short.MAX_VALUE else Short.MIN_VALUE },
        )
        for ((name, signal) in signals) {
            val reference = buildUnclippedReference(signal)
            val peak = reference.maxOf { abs(it) }
            val clipped = reference.count { abs(it) > Short.MAX_VALUE + REFERENCE_DIVISOR }
            val silence = PcmClip(ShortArray(reference.size), TARGET_RATE)
            val mixed = renderPreview(PcmClip(signal, SOURCE_RATE), silence, MixSettings()).samples
            val mixError = mixed.indices.maxOf { abs(mixed[it] - reference[it] / 2) }
            val peakDb = 20 * log10(peak / 32_768.0)
            report("$name: estimated peak %+.2f dBFS, clipped frames $clipped of ${reference.size}, mix error $mixError".format(peakDb))
        }
    }

    private class ToneFit(val amplitude: Double, val delayFrames: Double, val residualDb: Double)

    private fun buildTone(frequency: Int, frames: Int) =
        ShortArray(frames) { (TONE_AMPLITUDE * sin(2 * PI * frequency * it / SOURCE_RATE)).roundToInt().toShort() }

    /** Fits one sine over [frames] output frames, which must hold a whole number of its cycles. */
    private fun fitTone(samples: ShortArray, from: Int, frames: Int, frequency: Int, rate: Int): ToneFit {
        val step = 2 * PI * frequency / rate
        val range = from until from + frames
        val sineWeight = range.sumOf { samples[it] * sin(step * it) } * 2 / frames
        val cosineWeight = range.sumOf { samples[it] * cos(step * it) } * 2 / frames
        val residual = sqrt(
            range.sumOf {
                val error = samples[it] - sineWeight * sin(step * it) - cosineWeight * cos(step * it)
                error * error
            } / frames,
        )
        val amplitude = hypot(sineWeight, cosineWeight)
        return ToneFit(
            amplitude = amplitude,
            delayFrames = -atan2(cosineWeight, sineWeight) / step,
            residualDb = 20 * log10(residual / (amplitude / sqrt(2.0))),
        )
    }

    /** Runs a quieter copy through the real conversion, so nothing clips, then scales it back up. */
    private fun buildUnclippedReference(signal: ShortArray): IntArray {
        val quieter = ShortArray(signal.size) { (signal[it] / REFERENCE_DIVISOR).toShort() }
        val converted = resamplePcmUp(PcmClip(quieter, SOURCE_RATE), TARGET_RATE) {}.samples
        return IntArray(converted.size) { converted[it] * REFERENCE_DIVISOR }
    }

    private fun report(line: String) = println("PreviewQuality: $line")

    private companion object {
        const val SOURCE_RATE = 44_100
        const val TARGET_RATE = 48_000
        const val TONE_AMPLITUDE = 12_000.0
        const val FIT_FRAMES = 9_600 // 0.2 s: a whole number of cycles for any multiple of 5 Hz.
        const val REFERENCE_DIVISOR = 4

        // Set with the user from the first measurement, where these read 0.04 dB, -62.8 dB and 0.0004 frames.
        const val MAX_GAIN_ERROR_DB = 0.1
        const val MAX_OTHER_CONTENT_DB = -60.0
        const val MAX_DELAY_FRAMES = 0.01
    }
}
