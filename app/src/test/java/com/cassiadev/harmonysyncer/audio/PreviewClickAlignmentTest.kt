package com.cassiadev.harmonysyncer.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Both tracks get the same clicks one second after the start and one second before the end of a
 * 30-second clip. Each track must put them on exactly the same output samples, with no drift.
 */
class PreviewClickAlignmentTest {
    @Test
    fun `thirty second clips keep start and end clicks on the same output sample`() {
        assertClicksAligned { a, b, settings -> preparePreview(a, b, settings) }
        assertClicksAligned { a, b, settings -> renderPreview(a, b, settings) }
    }

    @Test
    fun `thirty second clips stay aligned when a preview reuses normalized clips`() {
        assertClicksAligned { a, b, settings ->
            val (normalizedA, normalizedB) = normalizePreviewRates(a, b)
            preparePreview(normalizedA, normalizedB, settings)
        }
    }

    private fun assertClicksAligned(render: (PcmClip, PcmClip, MixSettings) -> PcmClip) {
        for ((rateA, rateB) in listOf(48_000 to 48_000, 44_100 to 48_000, 48_000 to 44_100)) {
            val a = clickTrack(rateA)
            val b = clickTrack(rateB)
            val rate = maxOf(rateA, rateB)
            val expected = listOf(CLICK_START_SECONDS * rate, (CLIP_SECONDS - CLICK_END_MARGIN_SECONDS) * rate)
            val onlyA = render(a, b, MixSettings(volumeB = 0f))
            val onlyB = render(a, b, MixSettings(volumeA = 0f))
            assertEquals("A clicks at $rateA + $rateB Hz", expected, clickFrames(onlyA))
            assertEquals("B clicks at $rateA + $rateB Hz", expected, clickFrames(onlyB))
        }
    }

    private fun clickTrack(rate: Int) = PcmClip(
        ShortArray(CLIP_SECONDS * rate).apply {
            this[CLICK_START_SECONDS * rate] = 20_000
            this[(CLIP_SECONDS - CLICK_END_MARGIN_SECONDS) * rate] = 20_000
        },
        rate,
    )

    /** The loudest frame in each half: the resampler's ringing stays below the click peak. */
    private fun clickFrames(clip: PcmClip): List<Int> {
        val half = clip.samples.size / 2
        fun loudest(range: IntRange) = range.maxBy { abs(clip.samples[it].toInt()) }
        return listOf(loudest(0 until half), loudest(half until clip.samples.size))
    }

    private companion object {
        const val CLIP_SECONDS = 30
        const val CLICK_START_SECONDS = 1
        const val CLICK_END_MARGIN_SECONDS = 1
    }
}
