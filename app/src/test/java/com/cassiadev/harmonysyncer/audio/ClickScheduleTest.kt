package com.cassiadev.harmonysyncer.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickScheduleTest {
    private val bpms = listOf(40, 60, 90, 97, 120, 133, 160, 240)
    private val rates = listOf(44_100, 48_000)

    @Test
    fun `click frames stay within half a sample for an hour`() {
        for (bpm in bpms) for (rate in rates) {
            for (n in 0L..bpm * 60L) {
                val frame = clickFrameAt(n, bpm, rate)
                // |frame − n·60·sr/bpm| ≤ 0.5, scaled by 2·bpm to stay in integers.
                val error = abs(2L * bpm * frame - 2L * n * 60 * rate)
                assertTrue("bpm $bpm rate $rate beat $n error $error", error <= bpm)
            }
        }
    }

    @Test
    fun `click intervals are the floor or ceiling of the ideal interval`() {
        for (bpm in bpms) for (rate in rates) {
            val floor = 60L * rate / bpm
            val allowed = if (60L * rate % bpm == 0L) setOf(floor) else setOf(floor, floor + 1)
            for (n in 1L..bpm * 60L) {
                val interval = clickFrameAt(n, bpm, rate) - clickFrameAt(n - 1, bpm, rate)
                assertTrue("bpm $bpm rate $rate beat $n interval $interval", interval in allowed)
            }
        }
        assertTrue((1L..7_200L).all { clickFrameAt(it, 120, 48_000) - clickFrameAt(it - 1, 120, 48_000) == 24_000L })
    }

    @Test
    fun `ties round up`() {
        assertEquals(16_538L, clickFrameAt(1, 160, 44_100))
    }

    @Test
    fun `beat index`() {
        assertEquals(-1L, beatAt(-1, 120))
        assertEquals(0L, beatAt(0, 120))
        assertEquals(0L, beatAt(499_999_999, 120))
        assertEquals(1L, beatAt(500_000_000, 120))
        assertEquals(5_820L, beatAt(3_600_000_000_000, 97))
    }

    @Test
    fun `frame and time convert both ways`() {
        val stamp = FrameTime(1_000, 5_000_000_000)
        assertEquals(49_000L, frameAt(6_000_000_000, stamp, 48_000))
        assertEquals(-47_000L, frameAt(4_000_000_000, stamp, 48_000))
        assertEquals(6_000_000_000L, timeNanosAt(49_000, stamp, 48_000))
        assertEquals(4_000_000_000L, timeNanosAt(-47_000, stamp, 48_000))
    }

    @Test
    fun `tap tempo`() {
        fun taps(vararg seconds: Double) = seconds.map { (it * 1_000_000_000).toLong() }
        assertEquals(120, estimateTapBpm(taps(0.0, 0.5)))
        assertEquals(120, estimateTapBpm(taps(0.0, 0.5, 1.0, 1.5, 2.0, 2.5)))
        // Only the intervals after the last pause longer than 2 s count.
        assertEquals(100, estimateTapBpm(taps(0.0, 0.5, 1.0, 3.5, 4.1)))
        assertEquals(240, estimateTapBpm(taps(0.0, 0.1, 0.2)))
        assertEquals(40, estimateTapBpm(taps(0.0, 1.9, 3.8)))
        assertNull(estimateTapBpm(taps(0.0)))
        assertNull(estimateTapBpm(emptyList()))
        // A single tap after a pause starts a new count.
        assertNull(estimateTapBpm(taps(0.0, 0.5, 3.0)))
    }

    @Test
    fun `tap tempo averages the last four intervals`() {
        // 1.0 s, then four 0.5 s intervals: only the last four count.
        val taps = listOf(0L, 1_000_000_000, 1_500_000_000, 2_000_000_000, 2_500_000_000, 3_000_000_000)
        assertEquals(120, estimateTapBpm(taps))
    }

    @Test
    fun `timestamp wait`() {
        val fallback = FrameTime(0, 0)
        val measured = TimestampWait(0)
        assertNull(measured.offer(FrameTime(100, 1_000), 0, fallback))
        assertEquals(FrameTime(148, 2_000) to true, measured.offer(FrameTime(148, 2_000), 0, fallback))

        val stalled = TimestampWait(0)
        assertNull(stalled.offer(FrameTime(100, 1_000), 0, fallback))
        assertNull(stalled.offer(FrameTime(100, 2_000), 0, fallback))

        val missing = TimestampWait(0)
        assertNull(missing.offer(null, 999_999_999, fallback))
        assertEquals(fallback to false, missing.offer(null, 1_000_000_000, fallback))
    }

    @Test
    fun `recording uses the metronome by default`() {
        assertTrue(MetronomeSettings().useForRecording)
    }

    @Test
    fun `settings reject values outside the range`() {
        assertThrows(IllegalArgumentException::class.java) { MetronomeSettings(bpm = 39) }
        assertThrows(IllegalArgumentException::class.java) { MetronomeSettings(bpm = 241) }
        assertThrows(IllegalArgumentException::class.java) { MetronomeSettings(beatsPerBar = 5) }
        MetronomeSettings(bpm = 40, beatsPerBar = 6)
        MetronomeSettings(bpm = 240, beatsPerBar = 2)
    }
}
