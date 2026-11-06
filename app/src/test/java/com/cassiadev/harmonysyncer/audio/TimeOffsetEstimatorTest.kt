package com.cassiadev.harmonysyncer.audio

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Known answers for the estimator, in the design's sign: B's time minus A's, so a later B is positive. */
class TimeOffsetEstimatorTest {
    private val signal = buildTimingSignal(20, RATE)

    @Test
    fun `identical clips read as zero`() {
        assertEveryWindow(0, estimateTimeOffsets(buildClip(signal), buildClip(signal)))
    }

    @Test
    fun `a later B reads as a positive offset`() {
        assertEveryWindow(240, estimateTimeOffsets(buildClip(signal), buildClip(prepend(240, signal))))
    }

    @Test
    fun `an earlier B reads as a negative offset`() {
        assertEveryWindow(-240, estimateTimeOffsets(buildClip(prepend(240, signal)), buildClip(signal)))
    }

    @Test
    fun `a start difference of several seconds is found`() {
        assertEveryWindow(240_000, estimateTimeOffsets(buildClip(signal), buildClip(prepend(240_000, signal))))
    }

    @Test
    fun `different rates are compared at the higher rate`() {
        val source = buildTimingSignal(20, 44_100)
        val raised = resamplePcmUp(PcmClip(source, 44_100), RATE) {}.samples

        val estimate = estimateTimeOffsets(PcmClip(source, 44_100), buildClip(prepend(480, raised)))

        assertEquals(RATE, estimate.sampleRate)
        estimate.windows.forEach { assertEquals(480.0, it.offsetFrames.toDouble(), 1.0) }
    }

    /** One extra sample per 48,000: B's sound is 60 frames (1.25 ms) late at 60 seconds. */
    @Test
    fun `a known time scale difference grows with position`() {
        val long = buildTimingSignal(50, RATE)
        val stretched = resamplePcmUp(buildClip(long), RATE + 1) {}.samples

        val estimate = estimateTimeOffsets(buildClip(long), buildClip(stretched))

        estimate.windows.forEach { assertEquals(it.centerFrame / 48_000.0, it.offsetFrames.toDouble(), 1.0) }
        assertEquals(1.25, estimate.growthMsPerMinute, 0.1)
    }

    @Test
    fun `silence is uncertain`() {
        val silence = buildClip(ShortArray(signal.size))

        assertFalse(estimateTimeOffsets(buildClip(signal), silence).isCertain)
        assertFalse(estimateTimeOffsets(silence, buildClip(signal)).isCertain)
    }

    @Test
    fun `a repeating tone is uncertain`() {
        val tone = ShortArray(signal.size) { (12_000 * sin(2 * PI * 440 * it / RATE)).roundToInt().toShort() }

        assertFalse(estimateTimeOffsets(buildClip(tone), buildClip(tone)).isCertain)
    }

    @Test
    fun `clips that share nothing are uncertain`() {
        assertFalse(estimateTimeOffsets(buildClip(signal), buildClip(buildTimingSignal(20, RATE, seed = 1))).isCertain)
    }

    @Test
    fun `a long silent lead in does not move the start window`() {
        val delayed = buildClip(prepend(480_000, signal))

        val estimate = estimateTimeOffsets(delayed, delayed)

        assertTrue(estimate.isCertain)
        assertTrue("${estimate.windows.first().centerFrame}", estimate.windows.first().centerFrame >= 480_000)
    }

    @Test
    fun `B shorter than A is uncertain instead of failing`() {
        assertFalse(estimateTimeOffsets(buildClip(signal), buildClip(signal.copyOf(10 * RATE))).isCertain)
    }

    private fun assertEveryWindow(expectedFrames: Int, estimate: OffsetEstimate) {
        assertEquals(List(3) { expectedFrames }, estimate.windows.map { it.offsetFrames })
        assertTrue(estimate.windows.toString(), estimate.isCertain)
    }

    private fun buildClip(samples: ShortArray) = PcmClip(samples, RATE)

    private fun prepend(frames: Int, samples: ShortArray) = ShortArray(frames) + samples

    private companion object {
        const val RATE = 48_000
    }
}
