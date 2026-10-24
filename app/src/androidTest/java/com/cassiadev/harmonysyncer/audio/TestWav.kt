package com.cassiadev.harmonysyncer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal PCM16 mono WAV file for device tests that go through the real loader. */
internal fun buildWavBytes(samples: ShortArray, rate: Int): ByteArray {
    val dataSize = samples.size * Short.SIZE_BYTES
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".encodeToByteArray()).putInt(36 + dataSize).put("WAVE".encodeToByteArray())
        .put("fmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".encodeToByteArray()).putInt(dataSize)
    buffer.asShortBuffer().put(samples)
    return buffer.array()
}
