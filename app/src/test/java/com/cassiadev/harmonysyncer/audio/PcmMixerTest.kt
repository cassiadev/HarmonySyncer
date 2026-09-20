package com.cassiadev.harmonysyncer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmMixerTest {
    @Test
    fun `mixes two tracks with equal gain`() {
        val result = mixPcm16(
            shortArrayOf(10_000, -10_000, 4_000),
            shortArrayOf(6_000, -6_000, 0),
        )

        assertArrayEquals(shortArrayOf(8_000, -8_000, 2_000), result)
    }

    @Test
    fun `pads the shorter B track with silence`() {
        val result = mixPcm16(shortArrayOf(10_000, 10_000), shortArrayOf(6_000))

        assertArrayEquals(shortArrayOf(8_000, 5_000), result)
    }

    @Test
    fun `pads the shorter A track with silence`() {
        val result = mixPcm16(shortArrayOf(10_000), shortArrayOf(6_000, -6_000))

        assertArrayEquals(shortArrayOf(8_000, -3_000), result)
    }

    @Test
    fun `default gains preserve identical samples at pcm16 limits`() {
        val track = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)

        assertArrayEquals(track, mixPcm16(track, track))
    }

    @Test
    fun `saturates high gain samples to pcm16 limits without wraparound`() {
        val track = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)

        val result = mixPcm16(track, track, gainA = 1f, gainB = 1f)

        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE), result)
    }

    @Test
    fun `applies each custom gain to its own track`() {
        val result = mixPcm16(
            trackA = shortArrayOf(10_000, -10_000),
            trackB = shortArrayOf(6_000, -6_000),
            gainA = 0.25f,
            gainB = 0.75f,
        )

        assertArrayEquals(shortArrayOf(7_000, -7_000), result)
    }

    @Test
    fun `rounds fractional samples with ties toward positive infinity`() {
        val result = mixPcm16(shortArrayOf(1, -1, 3, -3), shortArrayOf())

        assertArrayEquals(shortArrayOf(1, 0, 2, -1), result)
    }

    @Test
    fun `zero gain mutes the corresponding track`() {
        val trackA = shortArrayOf(10_000, -10_000)
        val trackB = shortArrayOf(6_000, -6_000)

        assertArrayEquals(trackB, mixPcm16(trackA, trackB, gainA = 0f, gainB = 1f))
        assertArrayEquals(trackA, mixPcm16(trackA, trackB, gainA = 1f, gainB = 0f))
        assertArrayEquals(shortArrayOf(0, 0), mixPcm16(trackA, trackB, gainA = 0f, gainB = 0f))
    }

    @Test
    fun `handles one or both inputs being empty`() {
        assertArrayEquals(shortArrayOf(), mixPcm16(shortArrayOf(), shortArrayOf()))
        assertArrayEquals(shortArrayOf(5_000), mixPcm16(shortArrayOf(10_000), shortArrayOf()))
        assertArrayEquals(shortArrayOf(-3_000), mixPcm16(shortArrayOf(), shortArrayOf(-6_000)))
    }

    @Test
    fun `does not modify or share storage with input arrays`() {
        val trackA = shortArrayOf(10_000, -10_000)
        val trackB = shortArrayOf(6_000)
        val originalA = trackA.copyOf()
        val originalB = trackB.copyOf()

        val result = mixPcm16(trackA, trackB)

        assertNotSame(trackA, result)
        assertNotSame(trackB, result)
        result[0] = 0
        assertArrayEquals(originalA, trackA)
        assertArrayEquals(originalB, trackB)
    }

    @Test
    fun `rejects negative and non-finite gains for either track`() {
        val invalidGains = floatArrayOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (gain in invalidGains) {
            assertThrows(IllegalArgumentException::class.java) {
                mixPcm16(shortArrayOf(1), shortArrayOf(2), gainA = gain)
            }
            assertThrows(IllegalArgumentException::class.java) {
                mixPcm16(shortArrayOf(1), shortArrayOf(2), gainB = gain)
            }
        }
    }

    @Test
    fun `large finite gains do not overflow before samples are combined`() {
        val result = mixPcm16(
            trackA = shortArrayOf(2, 2, -2),
            trackB = shortArrayOf(-2, 2, -2),
            gainA = Float.MAX_VALUE,
            gainB = Float.MAX_VALUE,
        )

        assertArrayEquals(shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE), result)
    }
}
