package com.cassiadev.harmonysyncer.audio

import java.io.InputStream

const val WAV_MAX_DURATION_SECONDS = 15
const val WAV_MAX_FILE_BYTES = 8 * 1024 * 1024

/**
 * Reads one RIFF/WAVE PCM16 mono clip without closing [input].
 * Unknown chunks and their padding are consumed without keeping them in memory.
 * Both the complete RIFF file and the decoded clip have fixed upper bounds.
 */
fun readWavPcm16(input: InputStream): PcmClip {
    val header = input.readExactly(12)
    require(header.chunkId(0) == "RIFF" && header.chunkId(8) == "WAVE") {
        "PCM 16비트 모노 WAV 파일을 선택해 주세요."
    }
    val riffSize = header.unsignedInt(4)
    require(riffSize >= 4) { "WAV 파일의 헤더가 올바르지 않습니다." }
    require(riffSize + 8 <= WAV_MAX_FILE_BYTES) {
        "WAV 파일 크기는 8 MiB 이하여야 합니다."
    }

    var remaining = riffSize - 4
    var sampleRate: Int? = null
    var samples: ShortArray? = null
    while (remaining > 0) {
        require(remaining >= 8) { "WAV 파일의 청크 헤더가 잘렸습니다." }
        val chunkHeader = input.readExactly(8)
        val size = chunkHeader.unsignedInt(4)
        val paddedSize = size + (size and 1L)
        require(paddedSize <= remaining - 8) { "WAV 파일의 청크 크기가 올바르지 않습니다." }
        when (chunkHeader.chunkId(0)) {
            "fmt " -> {
                require(sampleRate == null) { "WAV 파일에 형식 정보가 중복되어 있습니다." }
                val parsedRate = readFormat(input, size)
                sampleRate = parsedRate
                samples?.let { validateDuration(it.size, parsedRate) }
            }
            "data" -> {
                require(samples == null) { "WAV 파일에 오디오 데이터가 중복되어 있습니다." }
                require(size > 0 && size % 2 == 0L) {
                    "WAV 오디오 데이터가 비어 있거나 잘못되었습니다."
                }
                val sampleCount = (size / 2).toInt()
                sampleRate?.let { validateDuration(sampleCount, it) }
                samples = input.readSamples(sampleCount)
            }
            else -> input.discardExactly(size)
        }
        if ((size and 1L) != 0L) input.readExactly(1)
        remaining -= 8 + paddedSize
    }

    require(input.read() == -1) { "WAV 파일의 선언된 크기 뒤에 데이터가 있습니다." }
    val rate = requireNotNull(sampleRate) { "WAV 파일에 형식 정보가 없습니다." }
    val pcm = requireNotNull(samples) { "WAV 파일에 오디오 데이터가 없습니다." }
    validateDuration(pcm.size, rate)
    return PcmClip(samples = pcm, sampleRate = rate)
}

private fun readFormat(input: InputStream, size: Long): Int {
    require(size >= 16 && size != 17L) { "WAV 파일의 형식 정보가 잘렸습니다." }
    val format = input.readExactly(16)
    val tag = format.unsignedShort(0)
    val channels = format.unsignedShort(2)
    val rate = format.unsignedInt(4)
    val byteRate = format.unsignedInt(8)
    val blockAlign = format.unsignedShort(12)
    val bits = format.unsignedShort(14)
    require(tag == 1 && channels == 1 && bits == 16) {
        "PCM 16비트 모노 WAV 파일만 지원합니다."
    }
    require(rate in 1..PCM_MAX_SAMPLE_RATE.toLong()) {
        "WAV 샘플레이트는 1 Hz 이상, 192 kHz 이하여야 합니다."
    }
    require(byteRate == rate * 2 && blockAlign == 2) {
        "WAV 파일의 데이터 정렬 정보가 올바르지 않습니다."
    }
    if (size > 16) {
        val extensionSize = input.readExactly(2).unsignedShort(0)
        require(extensionSize.toLong() == size - 18) {
            "WAV 파일의 확장 형식 정보가 올바르지 않습니다."
        }
        input.discardExactly(size - 18)
    }
    return rate.toInt()
}

private fun validateDuration(sampleCount: Int, sampleRate: Int) {
    require(sampleCount.toLong() <= sampleRate.toLong() * WAV_MAX_DURATION_SECONDS) {
        "오디오 길이는 15초 이하여야 합니다."
    }
}

private fun InputStream.readSamples(sampleCount: Int): ShortArray {
    val samples = ShortArray(sampleCount)
    val buffer = ByteArray(8_192)
    var sampleOffset = 0
    while (sampleOffset < sampleCount) {
        val count = minOf(buffer.size / 2, sampleCount - sampleOffset)
        readFully(buffer, count * 2)
        for (index in 0 until count) {
            samples[sampleOffset + index] = buffer.unsignedShort(index * 2).toShort()
        }
        sampleOffset += count
    }
    return samples
}

private fun InputStream.discardExactly(count: Long) {
    val buffer = ByteArray(8_192)
    var remaining = count
    while (remaining > 0) {
        val size = minOf(remaining, buffer.size.toLong()).toInt()
        readFully(buffer, size)
        remaining -= size
    }
}

private fun InputStream.readExactly(count: Int): ByteArray =
    ByteArray(count).also { readFully(it, count) }

private fun InputStream.readFully(buffer: ByteArray, count: Int) {
    var offset = 0
    while (offset < count) {
        val read = read(buffer, offset, count - offset)
        require(read >= 0) { "WAV 파일이 끝까지 저장되지 않았습니다." }
        if (read == 0) {
            val byte = read()
            require(byte >= 0) { "WAV 파일이 끝까지 저장되지 않았습니다." }
            buffer[offset++] = byte.toByte()
        } else {
            offset += read
        }
    }
}

private fun ByteArray.chunkId(offset: Int): String =
    String(this, offset, 4, Charsets.US_ASCII)

private fun ByteArray.unsignedShort(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.unsignedInt(offset: Int): Long =
    unsignedShort(offset).toLong() or (unsignedShort(offset + 2).toLong() shl 16)
