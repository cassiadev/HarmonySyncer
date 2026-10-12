package com.cassiadev.harmonysyncer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns at most one native track and releases it after playback or cancellation. */
class PcmPreviewPlayer {
    private val playbackMutex = Mutex()
    private val sessionLock = Any()
    private var activeJob: Job? = null
    private var generation = 0L
    private var closed = false

    /** Loads one complete static buffer and waits for its final mono frame. */
    suspend fun play(clip: PcmClip) {
        require(clip.sampleRate > 0) { "음원의 샘플레이트가 올바르지 않습니다." }
        require(clip.samples.isNotEmpty()) { "재생할 음원이 비어 있습니다." }
        require(clip.samples.size <= Int.MAX_VALUE / Short.SIZE_BYTES) {
            "미리 듣기 음원이 너무 큽니다."
        }
        val ticket = synchronized(sessionLock) {
            check(!closed) { "미리 듣기가 종료되었습니다." }
            generation
        }

        withContext(Dispatchers.IO) {
            // A canceled session finishes releasing its track before a new one can start.
            playbackMutex.withLock {
                val job = currentCoroutineContext().job
                synchronized(sessionLock) {
                    if (closed || ticket != generation) throw CancellationException()
                    activeJob = job
                }
                var track: AudioTrack? = null
                try {
                    currentCoroutineContext().ensureActive()
                    track = buildTrack(clip)
                    check(track.state != AudioTrack.STATE_UNINITIALIZED) {
                        "이 기기에서 음원을 재생할 수 없습니다."
                    }
                    currentCoroutineContext().ensureActive()

                    // MODE_STATIC writes start at buffer offset zero on every call. A short
                    // write cannot be appended, so never play an incompletely loaded buffer.
                    val written = track.write(
                        clip.samples,
                        0,
                        clip.samples.size,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    check(written == clip.samples.size) {
                        "미리 듣기 음원을 모두 준비하지 못했습니다. 다시 재생해 주세요."
                    }
                    check(track.state == AudioTrack.STATE_INITIALIZED) {
                        "미리 듣기 재생을 준비하지 못했습니다."
                    }
                    currentCoroutineContext().ensureActive()
                    track.play()

                    // write() only loads the static buffer; it does not finish playback.
                    while ((track.playbackHeadPosition.toLong() and 0xffff_ffffL) < clip.samples.size) {
                        currentCoroutineContext().ensureActive()
                        check(track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            "미리 듣기 재생이 중단되었습니다. 다시 재생해 주세요."
                        }
                        delay(10)
                    }
                } finally {
                    // This block remains on IO even when the calling coroutine is canceled.
                    try {
                        track?.let { audioTrack ->
                            try {
                                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                                    runCatching { audioTrack.stop() }
                                }
                            } finally {
                                audioTrack.release()
                            }
                        }
                    } finally {
                        synchronized(sessionLock) {
                            if (activeJob === job) activeJob = null
                        }
                    }
                }
            }
        }
    }

    /** Cancels pending/current playback; native cleanup runs in the playback coroutine. */
    fun stop() {
        synchronized(sessionLock) {
            generation++
            activeJob?.cancel()
        }
    }

    fun close() {
        synchronized(sessionLock) {
            closed = true
            generation++
            activeJob?.cancel()
        }
    }

    private fun buildTrack(clip: PcmClip): AudioTrack = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(clip.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(clip.samples.size * Short.SIZE_BYTES)
            .build()
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("이 기기에서 선택한 샘플레이트를 재생할 수 없습니다.", failure)
    } catch (failure: UnsupportedOperationException) {
        throw IllegalStateException("이 기기에서 미리 듣기를 재생할 수 없습니다.", failure)
    }
}
