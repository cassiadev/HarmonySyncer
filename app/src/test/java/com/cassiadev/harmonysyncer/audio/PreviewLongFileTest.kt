package com.cassiadev.harmonysyncer.audio

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Files of the longest allowed duration must reach the output with every frame once and in order.
 * A dropped or repeated frame anywhere shifts everything after it, so the comparison fails there.
 */
class PreviewLongFileTest {
    private val renderPaths: List<Pair<String, (PcmClip, PcmClip, MixSettings) -> PcmClip>> = listOf(
        "preparePreview" to { a, b, settings -> preparePreview(a, b, settings) },
        "renderPreview" to { a, b, settings -> renderPreview(a, b, settings) },
        "normalized clips" to { a, b, settings ->
            val (normalizedA, normalizedB) = normalizePreviewRates(a, b)
            preparePreview(normalizedA, normalizedB, settings)
        },
    )

    @Test
    fun `same rate files keep every frame at full length and with the largest offset`() {
        val a = buildPatternTrack(seed = 7_919)
        val b = buildPatternTrack(seed = 104_729)
        val offsetFrames = MAX_OFFSET_MS * OUTPUT_RATE / 1_000
        for ((path, render) in renderPaths) {
            assertArrayEquals(
                "A through $path",
                buildExpectedOutput(a.samples, leadingSilence = 0),
                render(a, b, MixSettings(volumeB = 0f)).samples,
            )
            assertArrayEquals(
                "B delayed through $path",
                buildExpectedOutput(b.samples, leadingSilence = offsetFrames),
                render(a, b, MixSettings(volumeA = 0f, offsetMs = MAX_OFFSET_MS)).samples,
            )
        }
    }

    @Test
    fun `resampled files keep a click on every second at full length`() {
        val a = buildClickTrack(44_100)
        val b = buildClickTrack(OUTPUT_RATE)
        val expected = (1 until AUDIO_MAX_DURATION_SECONDS).map { it * OUTPUT_RATE }
        for ((path, render) in renderPaths) {
            val onlyA = render(a, b, MixSettings(volumeB = 0f))
            assertEquals("A length through $path", AUDIO_MAX_DURATION_SECONDS * OUTPUT_RATE, onlyA.samples.size)
            assertEquals("A clicks through $path", expected, findClickFrames(onlyA))
            assertEquals("B clicks through $path", expected, findClickFrames(render(a, b, MixSettings(volumeA = 0f))))
        }
    }

    /** Even values, so half volume is exact; neighbouring frames differ, so any shift is visible. */
    private fun buildPatternTrack(seed: Long) = PcmClip(
        ShortArray(AUDIO_MAX_DURATION_SECONDS * OUTPUT_RATE) { (((it * seed) % 32_768) - 16_384).toInt().times(2).toShort() },
        OUTPUT_RATE,
    )

    /** Builds what the mixer outputs for one track alone: half volume after the leading silence. */
    private fun buildExpectedOutput(samples: ShortArray, leadingSilence: Int) = ShortArray(leadingSilence + samples.size) {
        if (it < leadingSilence) 0 else (samples[it - leadingSilence] / 2).toShort()
    }

    /** Silence with a click (one loud sample, used as a time marker) on every whole second. */
    private fun buildClickTrack(rate: Int) = PcmClip(
        ShortArray(AUDIO_MAX_DURATION_SECONDS * rate).apply {
            for (second in 1 until AUDIO_MAX_DURATION_SECONDS) this[second * rate] = 20_000
        },
        rate,
    )

    /** For each whole second, the loudest sample within half a second of it: where its click landed. */
    private fun findClickFrames(clip: PcmClip) = (1 until AUDIO_MAX_DURATION_SECONDS).map { second ->
        (second * OUTPUT_RATE - OUTPUT_RATE / 2 until second * OUTPUT_RATE + OUTPUT_RATE / 2).maxBy { abs(clip.samples[it].toInt()) }
    }

    private companion object {
        const val OUTPUT_RATE = 48_000
        const val MAX_OFFSET_MS = 300
    }
}
