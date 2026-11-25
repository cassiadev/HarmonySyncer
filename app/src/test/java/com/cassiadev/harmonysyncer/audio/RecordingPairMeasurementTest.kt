package com.cassiadev.harmonysyncer.audio

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs only when a path is given; run it with --no-configuration-cache --rerun, or the path may not arrive.
 * Pairs are read at 13 positions, the count behind the recorded results; RECORDING_PAIR_WINDOWS changes it.
 */
class RecordingPairMeasurementTest {
    @Test
    fun `writes the timing signal when a path is given`() {
        val path = System.getenv("TIMING_SIGNAL_OUT")
        assumeTrue(path != null)

        File(path!!).writeBytes(encodeWavPcm16(PcmClip(buildTimingSignal(50, 48_000), 48_000)))
    }

    @Test
    fun `reports the offsets of a recorded pair when paths are given`() {
        val pathA = System.getenv("RECORDING_PAIR_A")
        val pathB = System.getenv("RECORDING_PAIR_B")
        assumeTrue(pathA != null && pathB != null)

        val clipA = File(pathA!!).inputStream().buffered().use(::readWavPcm16)
        val clipB = File(pathB!!).inputStream().buffered().use(::readWavPcm16)
        val estimate = estimateTimeOffsets(clipA, clipB, System.getenv("RECORDING_PAIR_WINDOWS")?.toInt() ?: 13)

        println("RecordingPair: A ${clipA.samples.size} frames at ${clipA.sampleRate} Hz, B ${clipB.samples.size} frames at ${clipB.sampleRate} Hz")
        println("RecordingPair: compared at ${estimate.sampleRate} Hz, certain ${estimate.isCertain}, growth %.3f ms per minute".format(estimate.growthMsPerMinute))
        val fitted = fitGrowthMsPerMinute(estimate.windows)
        println("RecordingPair: fitted growth ${fitted?.let { "%.3f ms per minute".format(it) } ?: "not available"}")
        for (window in estimate.windows) {
            val seconds = window.centerFrame.toDouble() / estimate.sampleRate
            val offsetMs = window.offsetFrames * 1_000.0 / estimate.sampleRate
            println("RecordingPair: at %.2f s: offset ${window.offsetFrames} frames (%.3f ms), peak ratio %.2f".format(seconds, offsetMs, window.peakRatio))
        }
    }
}
