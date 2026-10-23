package com.cassiadev.harmonysyncer.audio

import java.io.ByteArrayOutputStream

/** Minimal PCM16 mono WAV file for device tests that go through the real loader. */
internal fun buildWavBytes(samples: ShortArray, rate: Int): ByteArray {
    val output = ByteArrayOutputStream()
    fun writeNumber(value: Int, bytes: Int) {
        repeat(bytes) { output.write(value ushr (it * 8) and 0xff) }
    }
    fun writeAscii(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
    writeAscii("RIFF"); writeNumber(36 + samples.size * 2, 4); writeAscii("WAVE")
    writeAscii("fmt "); writeNumber(16, 4); writeNumber(1, 2); writeNumber(1, 2)
    writeNumber(rate, 4); writeNumber(rate * 2, 4); writeNumber(2, 2); writeNumber(16, 2)
    writeAscii("data"); writeNumber(samples.size * 2, 4)
    samples.forEach { writeNumber(it.toInt(), 2) }
    return output.toByteArray()
}
