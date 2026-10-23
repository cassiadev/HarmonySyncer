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
        val a = patternTrack(seed = 7_919)
        val b = patternTrack(seed = 104_729)
        val offsetFrames = MAX_OFFSET_MS * RATE / 1_000
        for ((path, render) in renderPaths) {
            assertArrayEquals(
                "A through $path",
                halved(a.samples, leadingSilence = 0),
                render(a, b, MixSettings(volumeB = 0f)).samples,
            )
            assertArrayEquals(
                "B delayed through $path",
                halved(b.samples, leadingSilence = offsetFrames),
                render(a, b, MixSettings(volumeA = 0f, offsetMs = MAX_OFFSET_MS)).samples,
            )
        }
    }

    @Test
    fun `resampled files keep a click on every second at full length`() {
        val a = clickTrack(44_100)
        val b = clickTrack(RATE)
        val expected = (1 until AUDIO_MAX_DURATION_SECONDS).map { it * RATE }
        for ((path, render) in renderPaths) {
            val onlyA = render(a, b, MixSettings(volumeB = 0f))
            assertEquals("A length through $path", AUDIO_MAX_DURATION_SECONDS * RATE, onlyA.samples.size)
            assertEquals("A clicks through $path", expected, findClickFrames(onlyA))
            assertEquals("B clicks through $path", expected, findClickFrames(render(a, b, MixSettings(volumeA = 0f))))
        }
    }

    /** Even values, so half volume is exact; neighbouring frames differ, so any shift is visible. */
    private fun patternTrack(seed: Long) = PcmClip(
        ShortArray(AUDIO_MAX_DURATION_SECONDS * RATE) { (((it * seed) % 32_768) - 16_384).toInt().times(2).toShort() },
        RATE,
    )

    /** The mixer halves each track; a muted partner adds nothing. */
    private fun halved(samples: ShortArray, leadingSilence: Int) = ShortArray(leadingSilence + samples.size) {
        if (it < leadingSilence) 0 else (samples[it - leadingSilence] / 2).toShort()
    }

    /** Silence with a click (one loud sample, used as a time marker) on every whole second. */
    private fun clickTrack(rate: Int) = PcmClip(
        ShortArray(AUDIO_MAX_DURATION_SECONDS * rate).apply {
            for (second in 1 until AUDIO_MAX_DURATION_SECONDS) this[second * rate] = 20_000
        },
        rate,
    )

    /** For each whole second, the loudest sample within half a second of it: where its click landed. */
    private fun findClickFrames(clip: PcmClip) = (1 until AUDIO_MAX_DURATION_SECONDS).map { second ->
        (second * RATE - RATE / 2 until second * RATE + RATE / 2).maxBy { abs(clip.samples[it].toInt()) }
    }

    private companion object {
        const val RATE = 48_000
        const val MAX_OFFSET_MS = 300
    }
}
