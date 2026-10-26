package com.cassiadev.harmonysyncer.audio

private const val WAV_HEADER_BYTES = 44

/**
 * Encodes one mono PCM16 clip as a RIFF/WAVE file that [readWavPcm16] reads back.
 * Uses no `java.*` API so the writer can move to common Kotlin code unchanged.
 */
fun encodeWavPcm16(clip: PcmClip): ByteArray {
    require(clip.samples.isNotEmpty()) { "저장할 녹음이 비어 있습니다." }
    val dataBytes = clip.samples.size * Short.SIZE_BYTES
    val bytes = ByteArray(WAV_HEADER_BYTES + dataBytes)
    bytes.writeAscii(0, "RIFF")
    bytes.writeIntLe(4, WAV_HEADER_BYTES - 8 + dataBytes)
    bytes.writeAscii(8, "WAVE")
    bytes.writeAscii(12, "fmt ")
    bytes.writeIntLe(16, 16)
    bytes.writeShortLe(20, 1) // PCM
    bytes.writeShortLe(22, 1) // mono
    bytes.writeIntLe(24, clip.sampleRate)
    bytes.writeIntLe(28, clip.sampleRate * Short.SIZE_BYTES)
    bytes.writeShortLe(32, Short.SIZE_BYTES)
    bytes.writeShortLe(34, 16)
    bytes.writeAscii(36, "data")
    bytes.writeIntLe(40, dataBytes)
    clip.samples.forEachIndexed { index, sample ->
        bytes.writeShortLe(WAV_HEADER_BYTES + index * Short.SIZE_BYTES, sample.toInt())
    }
    return bytes
}

private fun ByteArray.writeAscii(offset: Int, text: String) {
    text.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
}

private fun ByteArray.writeShortLe(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = (value shr 8 and 0xff).toByte()
}

private fun ByteArray.writeIntLe(offset: Int, value: Int) {
    writeShortLe(offset, value)
    writeShortLe(offset + 2, value shr 16)
}
