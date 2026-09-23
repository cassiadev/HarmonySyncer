package com.cassiadev.harmonysyncer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAlignmentAndMixingTest {
    @Test
    fun `aligns the sound start points and mixes both tracks to the end`() {
        val trackA = ShortArray(483).apply {
            this[480] = 20_000
            this[481] = 10_000
            this[482] = 4_000
        }
        val trackB = ShortArray(1_444).apply {
            this[1_440] = 12_000
            this[1_441] = 6_000
            this[1_442] = 2_000
            this[1_443] = 8_000
        }
        val originalA = trackA.copyOf()
        val originalB = trackB.copyOf()

        val aligned = alignFromOnsets(trackA, 480, trackB, 1_440, 48_000)
        val mixed = mixPcm16(aligned.trackA, aligned.trackB)

        assertEquals(960, aligned.originalOffsetFrames)
        assertEquals(20.0, aligned.originalOffsetMs, 0.001)
        assertArrayEquals(shortArrayOf(16_000, 8_000, 3_000, 4_000), mixed)
        assertArrayEquals(shortArrayOf(20_000, 10_000, 4_000, 0), aligned.trackA)
        assertArrayEquals(shortArrayOf(12_000, 6_000, 2_000, 8_000), aligned.trackB)
        assertArrayEquals(originalA, trackA)
        assertArrayEquals(originalB, trackB)
    }

    @Test
    fun `20ms delayed copy becomes identical after alignment and mixing`() {
        val original = shortArrayOf(12_000, -9_000, Short.MAX_VALUE, Short.MIN_VALUE, 0, 2_000)
        val delayed = ShortArray(960) + original

        val aligned = alignFromOnsets(
            trackA = original,
            onsetA = 0,
            trackB = delayed,
            onsetB = 960,
            sampleRate = 48_000,
        )
        val mixed = mixPcm16(
            aligned.trackA, aligned.trackB,
            gainA = 0.5f, gainB = 0.5f,
        )

        assertEquals(960, aligned.originalOffsetFrames)
        assertEquals(20.0, aligned.originalOffsetMs, 0.000001)
        assertArrayEquals(original, aligned.trackA)
        assertArrayEquals(original, aligned.trackB)
        assertArrayEquals(original, mixed)
    }
}
