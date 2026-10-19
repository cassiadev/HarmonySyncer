package com.cassiadev.harmonysyncer.audio

import java.io.ByteArrayOutputStream

/** Minimal PCM16 mono WAV file for device tests that go through the real loader. */
internal fun wavBytes(samples: ShortArray, rate: Int): ByteArray {
    val output = ByteArrayOutputStream()
    fun number(value: Int, bytes: Int) {
        repeat(bytes) { output.write(value ushr (it * 8) and 0xff) }
    }
    fun id(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
    id("RIFF"); number(36 + samples.size * 2, 4); id("WAVE")
    id("fmt "); number(16, 4); number(1, 2); number(1, 2)
    number(rate, 4); number(rate * 2, 4); number(2, 2); number(16, 2)
    id("data"); number(samples.size * 2, 4)
    samples.forEach { number(it.toInt(), 2) }
    return output.toByteArray()
}
