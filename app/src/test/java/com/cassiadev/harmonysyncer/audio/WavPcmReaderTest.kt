package com.cassiadev.harmonysyncer.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WavPcmReaderTest {
    @Test
    fun `decodes signed little endian samples at the declared sample rate`() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        for (rate in intArrayOf(8_000, 16_000, 44_100, 48_000, 96_000, 192_000)) {
            val clip = readWavPcm16(buildWave(buildFormatChunk(rate = rate), buildDataChunk(samples)).inputStream())

            assertEquals(rate, clip.sampleRate)
            assertArrayEquals(samples, clip.samples)
        }
    }

    @Test
    fun `supports data appearing before the format chunk`() {
        val clip = readWavPcm16(buildWave(buildDataChunk(shortArrayOf(-123, 456)), buildFormatChunk()).inputStream())

        assertArrayEquals(shortArrayOf(-123, 456), clip.samples)
        assertEquals(48_000, clip.sampleRate)
    }

    @Test
    fun `scans unknown chunks and odd padding before and after audio`() {
        val clip = readWavPcm16(
            buildWave(
                buildChunk("JUNK", byteArrayOf(1, 2, 3)),
                buildFormatChunk(),
                buildChunk("LIST", byteArrayOf(4, 5)),
                buildDataChunk(shortArrayOf(42)),
                buildChunk("note", byteArrayOf(6)),
            ).inputStream(),
        )

        assertArrayEquals(shortArrayOf(42), clip.samples)
    }

    @Test
    fun `accepts PCM format extensions with their declared length`() {
        for (extension in listOf(byteArrayOf(), byteArrayOf(1, 2, 3))) {
            val clip = readWavPcm16(
                buildWave(buildFormatChunk(extension = extension), buildDataChunk(shortArrayOf(7))).inputStream(),
            )

            assertArrayEquals(shortArrayOf(7), clip.samples)
        }
    }

    @Test
    fun `limit messages state the problem and the fix`() {
        assertEquals("60초가 넘는 파일입니다. 60초 이하 파일을 선택해 주세요.", AUDIO_TOO_LONG_MESSAGE)
        assertEquals("파일이 너무 큽니다. 32 MiB 이하 파일을 선택해 주세요.", AUDIO_TOO_LARGE_MESSAGE)
    }

    @Test
    fun `accepts exactly sixty seconds and rejects one extra sample`() {
        val rate = 8
        assertEquals(
            480,
            readWavPcm16(buildWave(buildFormatChunk(rate = rate), buildDataChunk(ShortArray(480))).inputStream()).samples.size,
        )

        val exception = assertInvalid(buildWave(buildFormatChunk(rate = rate), buildDataChunk(ShortArray(481))))
        assertEquals(AUDIO_TOO_LONG_MESSAGE, exception.message)
    }

    @Test
    fun `checks duration when data precedes format information`() {
        assertEquals(
            480,
            readWavPcm16(buildWave(buildDataChunk(ShortArray(480)), buildFormatChunk(rate = 8)).inputStream()).samples.size,
        )
        val exception = assertInvalid(buildWave(buildDataChunk(ShortArray(481)), buildFormatChunk(rate = 8)))

        assertEquals(AUDIO_TOO_LONG_MESSAGE, exception.message)
    }

    @Test
    fun `accepts a full minute at the maximum supported sample rate`() {
        val header = buildRiffHeader(23_040_036L) + buildFormatChunk(rate = 192_000) +
            encodeAscii("data") + encodeU32(23_040_000L)
        SequenceInputStream(header.inputStream(), ByteArray(23_040_000).inputStream()).use { stream ->
            val clip = readWavPcm16(stream)

            assertEquals(192_000, clip.sampleRate)
            assertEquals(11_520_000, clip.samples.size)
        }
    }

    @Test
    fun `rejects files over the total limit before reading chunk data`() {
        val header = buildRiffHeader(AUDIO_MAX_FILE_BYTES.toLong() - 7)
        val stream = ByteArrayInputStream(header)

        val exception = assertThrows(IllegalArgumentException::class.java) { readWavPcm16(stream) }

        assertEquals(AUDIO_TOO_LARGE_MESSAGE, exception.message)
        assertEquals(0, stream.available())
    }

    @Test
    fun `rejects unsigned riff lengths without overflow`() {
        val exception = assertInvalid(buildRiffHeader(0xffff_ffffL))

        assertEquals(AUDIO_TOO_LARGE_MESSAGE, exception.message)
    }

    @Test
    fun `includes unknown chunks in the total file bound`() {
        val knownChunks = buildFormatChunk() + buildDataChunk(shortArrayOf(1))
        val unknownHeader = encodeAscii("JUNK") + encodeU32(AUDIO_MAX_FILE_BYTES.toLong())
        val file = buildRiffHeader(4L + knownChunks.size + 8 + AUDIO_MAX_FILE_BYTES) + knownChunks + unknownHeader

        assertEquals(AUDIO_TOO_LARGE_MESSAGE, assertInvalid(file).message)
    }

    @Test
    fun `rejects every truncated initial header`() {
        val valid = buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1)))
        for (length in 0 until 12) assertInvalid(valid.copyOf(length))
    }

    @Test
    fun `rejects truncated audio instead of filling missing samples`() {
        val valid = buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1, 2)))

        assertInvalid(valid.copyOf(valid.size - 1))
    }

    @Test
    fun `detects truncated unknown chunks instead of trusting skip`() {
        val valid = buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1)), buildChunk("JUNK", byteArrayOf(1, 2, 3, 4)))

        assertInvalid(valid.copyOf(valid.size - 1))
    }

    @Test
    fun `rejects an odd chunk without its padding byte`() {
        assertInvalid(buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1)), encodeAscii("JUNK") + encodeU32(1) + byteArrayOf(2)))
    }

    @Test
    fun `rejects a truncated chunk header inside the declared container`() {
        assertInvalid(buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1)), byteArrayOf(1, 2)))
    }

    @Test
    fun `rejects invalid signatures and container sizes`() {
        val valid = buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1)))
        for ((offset, id) in listOf(0 to "RIFX", 0 to "RF64", 8 to "AIFF")) {
            assertInvalid(valid.copyOf().apply { encodeAscii(id).copyInto(this, offset) })
        }
        assertInvalid(buildRiffHeader(3))
    }

    @Test
    fun `rejects compressed extensible stereo and other bit depths`() {
        for (unsupported in listOf(
            buildFormatChunk(tag = 3),
            buildFormatChunk(tag = 0xfffe),
            buildFormatChunk(channels = 2),
            buildFormatChunk(bits = 8),
            buildFormatChunk(bits = 24),
        )) {
            assertTrue(assertInvalid(buildWave(unsupported, buildDataChunk(shortArrayOf(1)))).message.orEmpty().contains("모노"))
        }
    }

    @Test
    fun `rejects invalid sample rates byte rates and block alignment`() {
        for (malformed in listOf(
            buildFormatChunk(rate = 0),
            buildFormatChunk(rate = 0x8000_0000L, byteRate = 0),
            buildFormatChunk(byteRate = 95_999),
            buildFormatChunk(blockAlign = 1),
            buildFormatChunk(blockAlign = 4),
        )) {
            assertInvalid(buildWave(malformed, buildDataChunk(shortArrayOf(1))))
        }
    }

    @Test
    fun `rejects oversized sample rates even with a consistent pcm header`() {
        for (rate in longArrayOf(PCM_MAX_SAMPLE_RATE + 1L, Int.MAX_VALUE.toLong())) {
            val exception = assertInvalid(buildWave(buildFormatChunk(rate = rate), buildDataChunk(shortArrayOf(1))))

            assertTrue(exception.message.orEmpty().contains("192 kHz"))
        }
    }

    @Test
    fun `requires both format and audio chunks`() {
        assertInvalid(buildWave(buildFormatChunk()))
        assertInvalid(buildWave(buildDataChunk(shortArrayOf(1))))
        assertInvalid(buildWave(buildChunk("JUNK", byteArrayOf())))
    }

    @Test
    fun `rejects duplicate format and audio chunks`() {
        assertInvalid(buildWave(buildFormatChunk(), buildFormatChunk(), buildDataChunk(shortArrayOf(1))))
        assertInvalid(buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1)), buildDataChunk(shortArrayOf(2))))
    }

    @Test
    fun `rejects empty and incomplete pcm samples`() {
        assertInvalid(buildWave(buildFormatChunk(), buildChunk("data", byteArrayOf())))
        assertInvalid(buildWave(buildFormatChunk(), buildChunk("data", byteArrayOf(1))))
    }

    @Test
    fun `rejects truncated base and extension format information`() {
        for (length in listOf(0, 15, 17)) {
            assertInvalid(buildWave(buildChunk("fmt ", ByteArray(length)), buildDataChunk(shortArrayOf(1))))
        }
        val extensionMismatch = buildFormatPayload() + encodeU16(2)
        assertInvalid(buildWave(buildChunk("fmt ", extensionMismatch), buildDataChunk(shortArrayOf(1))))
    }

    @Test
    fun `rejects a chunk extending outside the riff container`() {
        val oversizedDataHeader = encodeAscii("data") + encodeU32(0xffff_ffffL)

        assertInvalid(buildWave(buildFormatChunk(), oversizedDataHeader))
    }

    @Test
    fun `rejects bytes outside the declared container`() {
        assertInvalid(buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1))) + byteArrayOf(2))
    }

    @Test
    fun `handles short reads and streams returning zero without hanging`() {
        val bytes = buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(Short.MIN_VALUE, -257, 0, 257, Short.MAX_VALUE)))
        val stream = object : ByteArrayInputStream(bytes) {
            private var returnZero = true

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                returnZero = true
                return super.read(buffer, offset, minOf(length, 3))
            }
        }

        assertArrayEquals(
            shortArrayOf(Short.MIN_VALUE, -257, 0, 257, Short.MAX_VALUE),
            readWavPcm16(stream).samples,
        )
    }

    @Test
    fun `decodes samples correctly across the streaming buffer boundary`() {
        val samples = ShortArray(4_103) { (it * 91 - 20_000).toShort() }

        assertArrayEquals(samples, readWavPcm16(buildWave(buildFormatChunk(), buildDataChunk(samples)).inputStream()).samples)
    }

    @Test
    fun `leaves stream ownership with the caller on success and failure`() {
        for (bytes in listOf(buildWave(buildFormatChunk(), buildDataChunk(shortArrayOf(1))), byteArrayOf())) {
            val stream = object : ByteArrayInputStream(bytes) {
                var closed = false

                override fun close() {
                    closed = true
                    super.close()
                }
            }
            if (bytes.isEmpty()) {
                assertThrows(IllegalArgumentException::class.java) { readWavPcm16(stream) }
            } else {
                readWavPcm16(stream)
            }
            assertFalse(stream.closed)
        }
    }

    @Test
    fun `propagates stream failures to the file loader`() {
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("provider failed")
        }

        assertThrows(IOException::class.java) { readWavPcm16(stream) }
    }

    private fun assertInvalid(bytes: ByteArray): IllegalArgumentException =
        assertThrows(IllegalArgumentException::class.java) { readWavPcm16(bytes.inputStream()) }

    private fun buildWave(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { chunks.forEach { write(it) } }.toByteArray()
        return buildRiffHeader(4L + body.size) + body
    }

    private fun buildRiffHeader(size: Long): ByteArray = encodeAscii("RIFF") + encodeU32(size) + encodeAscii("WAVE")

    private fun buildChunk(id: String, bytes: ByteArray): ByteArray =
        encodeAscii(id) + encodeU32(bytes.size.toLong()) + bytes + if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    private fun buildDataChunk(samples: ShortArray): ByteArray = buildChunk(
        "data",
        ByteArrayOutputStream().apply { samples.forEach { write(encodeU16(it.toInt())) } }.toByteArray(),
    )

    private fun buildFormatChunk(
        rate: Number = 48_000,
        tag: Int = 1,
        channels: Int = 1,
        bits: Int = 16,
        byteRate: Number = rate.toLong() * 2,
        blockAlign: Int = 2,
        extension: ByteArray? = null,
    ): ByteArray = buildChunk(
        "fmt ",
        buildFormatPayload(rate, tag, channels, bits, byteRate, blockAlign) +
            (extension?.let { encodeU16(it.size) + it } ?: byteArrayOf()),
    )

    private fun buildFormatPayload(
        rate: Number = 48_000,
        tag: Int = 1,
        channels: Int = 1,
        bits: Int = 16,
        byteRate: Number = rate.toLong() * 2,
        blockAlign: Int = 2,
    ): ByteArray = encodeU16(tag) + encodeU16(channels) + encodeU32(rate.toLong()) + encodeU32(byteRate.toLong()) +
        encodeU16(blockAlign) + encodeU16(bits)

    private fun encodeAscii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun encodeU16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun encodeU32(value: Long): ByteArray = ByteArray(4) { index -> (value ushr (index * 8)).toByte() }
}
