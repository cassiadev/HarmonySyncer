package com.cassiadev.harmonysyncer.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Decodes MPEG-4 AAC through the platform codec, averaging stereo into mono PCM16. */
internal suspend fun readM4aPcm16(input: InputStream): PcmClip {
    val context = currentCoroutineContext()
    val encoded = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        require(encoded.size().toLong() + count <= AUDIO_MAX_FILE_BYTES) {
            "오디오 파일 크기는 32 MiB 이하여야 합니다."
        }
        encoded.write(buffer, 0, count)
    }

    val bytes = encoded.toByteArray()
    validateMpeg4Boxes(bytes) { context.ensureActive() }
    ByteArrayMediaSource(bytes).use { source ->
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(source)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("M4A 파일이 손상됐거나 지원하지 않는 형식입니다.", failure)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            require(track != null) { "M4A 파일에 오디오가 없습니다." }
            val format = extractor.getTrackFormat(track)
            require(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") {
                "AAC 방식의 M4A 파일을 선택해 주세요. 이 파일의 압축 방식은 지원하지 않습니다."
            }
            validateOutputFormat(format)
            extractor.selectTrack(track)
            // Keep the extractor's encoder-delay/padding so the codec removes AAC priming.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var samples = ShortArray(16_384)
            var sampleCount = 0
            var inputEnded = false
            var outputEnded = false
            var lastProgress = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                context.ensureActive()
                require(SystemClock.elapsedRealtime() - lastProgress < 5_000) {
                    "M4A 파일을 읽는 중 응답이 멈췄습니다. 다른 파일을 선택해 주세요."
                }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val inputBuffer = checkNotNull(codec.getInputBuffer(index))
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                                "암호화된 M4A 파일은 지원하지 않습니다."
                            }
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    validateOutputFormat(output)
                    require(output.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                        AudioFormat.ENCODING_PCM_16BIT) { "이 기기의 M4A 오디오 변환 형식은 지원하지 않습니다." }
                    val nextRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val nextChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(sampleCount == 0 || (rate == nextRate && channels == nextChannels)) {
                        "M4A 파일의 오디오 형식이 중간에 바뀝니다. 다른 파일을 선택해 주세요."
                    }
                    rate = nextRate
                    channels = nextChannels
                    lastProgress = SystemClock.elapsedRealtime()
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            require(info.size % (channels * 2) == 0) { "M4A 오디오 데이터가 잘렸습니다." }
                            val frames = info.size / (channels * 2)
                            val limit = rate * AUDIO_MAX_DURATION_SECONDS
                            require(frames <= limit - sampleCount) { "오디오 길이는 60초 이하여야 합니다." }
                            if (sampleCount + frames > samples.size) {
                                samples = samples.copyOf(minOf(limit, maxOf(sampleCount + frames, samples.size * 2)))
                            }
                            val output = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            repeat(frames) {
                                val left = output.short.toInt()
                                samples[sampleCount++] = if (channels == 1) left.toShort()
                                else ((left + output.short.toInt()) / 2).toShort()
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        lastProgress = SystemClock.elapsedRealtime()
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            require(sampleCount > 0) { "M4A 파일에 오디오 데이터가 없습니다." }
            return PcmClip(samples.copyOf(sampleCount), rate)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            throw IllegalArgumentException("M4A 파일을 읽을 수 없습니다. 파일이 손상됐거나 압축 방식이 지원되지 않습니다.", failure)
        } catch (failure: IllegalStateException) {
            throw IllegalArgumentException("M4A 오디오를 변환할 수 없습니다. 다른 파일을 선택해 주세요.", failure)
        } finally {
            try {
                decoder?.release()
            } finally {
                extractor.release()
            }
        }
    }
}

private fun validateOutputFormat(format: MediaFormat) {
    require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) in 1..PCM_MAX_SAMPLE_RATE) {
        "M4A 샘플레이트는 1 Hz 이상, 192 kHz 이하여야 합니다."
    }
    require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) in 1..2) {
        "모노 또는 스테레오 M4A 파일을 선택해 주세요."
    }
}

/** The extractor reports truncated payload reads as EOF; reject incomplete boxes first. */
private fun validateMpeg4Boxes(bytes: ByteArray, checkActive: () -> Unit) {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    while (buffer.hasRemaining()) {
        checkActive()
        val remaining = buffer.remaining().toLong()
        val start = buffer.position()
        require(remaining >= 8) { "M4A 파일의 뒷부분이 잘렸습니다." }
        val size = buffer.int.toLong() and 0xffff_ffffL
        buffer.int // Box type; the platform extractor validates its contents.
        val headerSize = if (size == 1L) 16 else 8
        val length = when (size) {
            0L -> remaining
            1L -> {
                require(remaining >= 16) { "M4A 파일의 뒷부분이 잘렸습니다." }
                buffer.long
            }
            else -> size
        }
        require(length >= headerSize && length <= remaining) {
            "M4A 파일이 잘렸거나 파일 크기 정보가 올바르지 않습니다."
        }
        buffer.position(start + length.toInt())
    }
}

/** Makes cloud/document-provider streams seekable without keeping temporary files. */
private class ByteArrayMediaSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0) throw IOException("Negative media position")
        if (position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() = Unit
}
