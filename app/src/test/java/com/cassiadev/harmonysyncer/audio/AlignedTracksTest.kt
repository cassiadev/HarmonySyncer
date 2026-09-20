package com.cassiadev.harmonysyncer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignedTracksTest {
    @Test
    fun `equal sample contents and offsets have equal values and hashes`() {
        val first = AlignedTracks(shortArrayOf(1, -2), shortArrayOf(3, -4), 960, 20.0)
        val second = AlignedTracks(shortArrayOf(1, -2), shortArrayOf(3, -4), 960, 20.0)

        assertEquals(first, second)
        assertEquals(second, first)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(hashSetOf(first).contains(second))
    }

    @Test
    fun `a change to either track or either offset makes results unequal`() {
        val original = AlignedTracks(shortArrayOf(1, -2), shortArrayOf(3, -4), 960, 20.0)

        assertNotEquals(original, original.copy(trackA = shortArrayOf(1, -3)))
        assertNotEquals(original, original.copy(trackB = shortArrayOf(3)))
        assertNotEquals(original, original.copy(originalOffsetFrames = 480))
        assertNotEquals(original, original.copy(originalOffsetMs = 10.0))
    }

    @Test
    fun `preserves data class equality and hash consistency for NaN offsets`() {
        val first = AlignedTracks(shortArrayOf(1), shortArrayOf(2), 0, Double.NaN)
        val second = AlignedTracks(shortArrayOf(1), shortArrayOf(2), 0, Double.NaN)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `preserves data class distinction between positive and negative zero offsets`() {
        val positiveZero = AlignedTracks(shortArrayOf(1), shortArrayOf(2), 0, 0.0)
        val negativeZero = positiveZero.copy(originalOffsetMs = -0.0)

        assertNotEquals(positiveZero, negativeZero)
    }
}
