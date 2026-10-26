package com.cassiadev.harmonysyncer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavPcmWriterTest {
    @Test
    fun `round trips through the existing reader`() {
        for (samples in listOf(
            shortArrayOf(7),
            shortArrayOf(1, -2, 3),
            shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE),
        )) {
            val clip = readWavPcm16(encodeWavPcm16(PcmClip(samples, 44_100)).inputStream())

            assertArrayEquals(samples, clip.samples)
            assertEquals(44_100, clip.sampleRate)
        }
    }

    @Test
    fun `header sizes match the sample count`() {
        val bytes = encodeWavPcm16(PcmClip(ShortArray(3), 48_000))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44 + 6, bytes.size)
        assertEquals(36 + 6, header.getInt(4))
        assertEquals(6, header.getInt(40))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty clip`() {
        encodeWavPcm16(PcmClip(ShortArray(0), 48_000))
    }
}
