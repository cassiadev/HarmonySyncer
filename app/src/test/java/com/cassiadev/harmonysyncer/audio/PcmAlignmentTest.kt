package com.cassiadev.harmonysyncer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmAlignmentTest {
    @Test
    fun `aligns tracks whose onsets differ by 20 milliseconds`() {
        val trackA = ShortArray(4_800).apply {
            this[480] = 20_000
            this[481] = 10_000
        }
        val trackB = ShortArray(4_800).apply {
            this[1_440] = 20_000
            this[1_441] = 10_000
        }

        val result = alignFromOnsets(trackA, 480, trackB, 1_440, 48_000)

        assertEquals(960, result.originalOffsetFrames)
        assertEquals(20.0, result.originalOffsetMs, 0.001)
        assertEquals(4_320, result.trackA.size)
        assertEquals(4_320, result.trackB.size)
        assertEquals(20_000, result.trackA[0].toInt())
        assertEquals(20_000, result.trackB[0].toInt())
        assertEquals(10_000, result.trackA[1].toInt())
        assertEquals(10_000, result.trackB[1].toInt())
    }

    @Test
    fun `reports a negative offset when B starts before A`() {
        val result = alignFromOnsets(
            trackA = shortArrayOf(0, 0, 0, 1_000, -1_000),
            onsetA = 3,
            trackB = shortArrayOf(0, 2_000, -2_000),
            onsetB = 1,
            sampleRate = 1_000,
        )

        assertEquals(-2, result.originalOffsetFrames)
        assertEquals(-2.0, result.originalOffsetMs, 0.001)
        assertArrayEquals(shortArrayOf(1_000, -1_000), result.trackA)
        assertArrayEquals(shortArrayOf(2_000, -2_000), result.trackB)
    }

    @Test
    fun `trims equal onsets and returns zero offset`() {
        val result = alignFromOnsets(
            trackA = shortArrayOf(7, 1_000, -1_000),
            onsetA = 1,
            trackB = shortArrayOf(-7, 2_000, -2_000),
            onsetB = 1,
            sampleRate = 48_000,
        )

        assertEquals(0, result.originalOffsetFrames)
        assertEquals(0.0, result.originalOffsetMs, 0.0)
        assertArrayEquals(shortArrayOf(1_000, -1_000), result.trackA)
        assertArrayEquals(shortArrayOf(2_000, -2_000), result.trackB)
    }

    @Test
    fun `pads A with silence when its remaining samples end first`() {
        val result = alignFromOnsets(
            trackA = shortArrayOf(0, 1_000),
            onsetA = 1,
            trackB = shortArrayOf(0, 0, 2_000, 3_000, 4_000),
            onsetB = 2,
            sampleRate = 48_000,
        )

        assertArrayEquals(shortArrayOf(1_000, 0, 0), result.trackA)
        assertArrayEquals(shortArrayOf(2_000, 3_000, 4_000), result.trackB)
    }

    @Test
    fun `pads B based on remaining length even when its original track is longer`() {
        val result = alignFromOnsets(
            trackA = shortArrayOf(1_000, 2_000, 3_000),
            onsetA = 0,
            trackB = shortArrayOf(0, 0, 0, 4_000),
            onsetB = 3,
            sampleRate = 48_000,
        )

        assertArrayEquals(shortArrayOf(1_000, 2_000, 3_000), result.trackA)
        assertArrayEquals(shortArrayOf(4_000, 0, 0), result.trackB)
    }

    @Test
    fun `accepts onsets at the last sample`() {
        val result = alignFromOnsets(shortArrayOf(0, 4), 1, shortArrayOf(0, 0, 8), 2, 48_000)

        assertArrayEquals(shortArrayOf(4), result.trackA)
        assertArrayEquals(shortArrayOf(8), result.trackB)
    }

    @Test
    fun `preserves fractional milliseconds at other sample rates`() {
        val result = alignFromOnsets(shortArrayOf(4), 0, shortArrayOf(0, 8), 1, 44_100)

        assertEquals(1, result.originalOffsetFrames)
        assertEquals(0.022675736961451247, result.originalOffsetMs, 1e-12)
    }

    @Test
    fun `does not modify or share storage with input arrays`() {
        val trackA = shortArrayOf(1_000, -1_000)
        val trackB = shortArrayOf(2_000, -2_000)
        val originalA = trackA.copyOf()
        val originalB = trackB.copyOf()

        val result = alignFromOnsets(trackA, 0, trackB, 0, 48_000)

        assertArrayEquals(originalA, result.trackA)
        assertArrayEquals(originalB, result.trackB)
        assertNotSame(trackA, result.trackA)
        assertNotSame(trackB, result.trackB)
        assertNotSame(result.trackA, result.trackB)
        result.trackA[0] = 0
        result.trackB[0] = 0
        assertArrayEquals(originalA, trackA)
        assertArrayEquals(originalB, trackB)
    }

    @Test
    fun `rejects negative and out of bounds onset A`() {
        val track = shortArrayOf(1, 2)
        for (onset in intArrayOf(-1, track.size, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                alignFromOnsets(track, onset, track, 0, 48_000)
            }
        }
    }

    @Test
    fun `rejects negative and out of bounds onset B`() {
        val track = shortArrayOf(1, 2)
        for (onset in intArrayOf(-1, track.size, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                alignFromOnsets(track, 0, track, onset, 48_000)
            }
        }
    }

    @Test
    fun `rejects empty tracks because they cannot have an onset`() {
        assertThrows(IllegalArgumentException::class.java) {
            alignFromOnsets(shortArrayOf(), 0, shortArrayOf(1), 0, 48_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            alignFromOnsets(shortArrayOf(1), 0, shortArrayOf(), 0, 48_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            alignFromOnsets(shortArrayOf(), 0, shortArrayOf(), 0, 48_000)
        }
    }

    @Test
    fun `rejects zero and negative sample rates`() {
        for (sampleRate in intArrayOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                alignFromOnsets(shortArrayOf(1), 0, shortArrayOf(2), 0, sampleRate)
            }
        }
    }
}
