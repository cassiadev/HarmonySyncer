package com.cassiadev.harmonysyncer.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickTrackTest {
    private val rate = 48_000
    private val clickFrames = rate / 50

    /** Renders [from, until) in chunks of [chunk] frames with one cursor, as the player does. */
    private fun render(
        from: Long,
        until: Long,
        firstClickFrame: Long,
        cursor: ClickCursor,
        chunk: Int = (until - from).toInt(),
        muted: Boolean = false,
    ): ShortArray {
        val result = ShortArray((until - from).toInt())
        var frame = from
        while (frame < until) {
            val size = minOf(chunk.toLong(), until - frame).toInt()
            val out = ShortArray(size)
            renderClickChunk(out, frame, firstClickFrame, 120, 4, rate, muted, cursor)
            out.copyInto(result, (frame - from).toInt())
            frame += size
        }
        return result
    }

    private fun zeroCrossings(samples: ShortArray, from: Int, count: Int): Int =
        (from + 1 until from + count).count { (samples[it - 1] < 0) != (samples[it] < 0) }

    private fun firstNonZero(samples: ShortArray, from: Int = 0): Int = (from until samples.size).first { samples[it] != 0.toShort() }

    @Test
    fun `clicks start exactly on their frames`() {
        val out = render(0, 96_000, 1_000, ClickCursor())

        assertEquals(0.toShort(), out[1_000])
        assertEquals(1_001, firstNonZero(out))
        assertEquals(25_001, firstNonZero(out, 1_000 + clickFrames))
        val accent = zeroCrossings(out, 1_000, clickFrames)
        val normal = zeroCrossings(out, 25_000, clickFrames)
        assertTrue("accent $accent normal $normal", accent in (normal * 18 / 10)..(normal * 22 / 10))
    }

    @Test
    fun `chunked rendering equals one pass`() {
        val whole = render(0, 96_000, 1_000, ClickCursor())
        for (chunk in listOf(7, 480, 1_000)) {
            assertArrayEquals("chunk $chunk", whole, render(0, 96_000, 1_000, ClickCursor(), chunk))
        }
    }

    @Test
    fun `muted chunks are silent but keep the beat count`() {
        val cursor = ClickCursor()
        val muted = render(0, 26_000, 1_000, cursor, muted = true)
        val open = render(26_000, 60_000, 1_000, cursor)

        assertTrue(muted.all { it == 0.toShort() })
        // Beat 2 starts at 1 000 + 48 000.
        assertEquals(49_001 - 26_000, firstNonZero(open))
    }

    @Test
    fun `an earlier first frame skips beats already passed`() {
        val cursor = ClickCursor()
        render(0, 12_000, 1_000, cursor)
        // Beat 1 now falls at −5 000, already written; beat 2 at 19 000 is the next to sound, beat 3 at 43 000 is later.
        val after = render(12_000, 40_000, 1_000 - 30_000, cursor)

        assertEquals(19_001 - 12_000, firstNonZero(after))
        assertEquals(3L, cursor.nextBeat)
    }

    @Test
    fun `overlapping clicks keep both waveforms`() {
        // Beat 0 starts at 1 000; after a 2.5 ms chunk the first frame moves so beat 1 starts 5 ms after beat 0.
        val moved = 1_240L - 24_000
        val split = ClickCursor()
        render(0, 1_120, 1_000, split)
        val chunked = render(1_120, 1_500, moved, split) + render(1_500, 4_000, moved, split)
        val whole = ClickCursor()
        render(0, 1_120, 1_000, whole)
        val onePass = render(1_120, 4_000, moved, whole)

        val expected = ShortArray(4_000 - 1_120) { index ->
            val frame = 1_120 + index
            var sum = 0
            if (frame - 1_000 in 0 until clickFrames) sum += clickSampleAt(frame - 1_000, accent = true, rate)
            if (frame - 1_240 in 0 until clickFrames) sum += clickSampleAt(frame - 1_240, accent = false, rate)
            sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        assertArrayEquals(expected, chunked)
        assertArrayEquals(expected, onePass)
    }

    @Test
    fun `a later first frame never repeats a beat`() {
        val cursor = ClickCursor()
        val before = render(0, 1_240, 1_000, cursor)
        val after = render(1_240, 48_000, 13_000, cursor)
        val uninterrupted = render(0, 48_000, 1_000, ClickCursor())

        // Beat 0 finishes from where it started.
        assertArrayEquals(uninterrupted.copyOfRange(0, 1_000 + clickFrames), (before + after).copyOfRange(0, 1_000 + clickFrames))
        // Beat 0 does not sound again at the moved position; beat 1 does, at 13 000 + 24 000.
        assertTrue(after.copyOfRange(13_000 - 1_240, 13_000 - 1_240 + clickFrames).all { it == 0.toShort() })
        assertEquals(37_001 - 1_240, firstNonZero(after, 1_000 + clickFrames - 1_240))
    }

    @Test
    fun `first click frame comes after written frames`() {
        assertEquals(48_480L, chooseFirstClickFrame(48_000, 480, 40_000))
        assertEquals(7_200L, chooseFirstClickFrame(0, 480, 7_200))
    }

    @Test
    fun `click frame moves only past two milliseconds`() {
        assertFalse(shouldMoveClickFrame(10_000, 10_096, 48_000))
        assertTrue(shouldMoveClickFrame(10_000, 10_097, 48_000))
        assertTrue(shouldMoveClickFrame(10_000, 9_903, 48_000))
    }

    @Test
    fun `recording clicks only on listed outputs`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )) {
            assertTrue("type $type", allowsRecordingClick(type))
        }
        for (type in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            null,
        )) {
            assertFalse("type $type", allowsRecordingClick(type))
        }
    }
}
