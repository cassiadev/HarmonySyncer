package com.cassiadev.harmonysyncer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

// 44.1 kHz is the only capture rate Android guarantees on every device.
private val RECORDING_SAMPLE_RATES = intArrayOf(48_000, 44_100)

/**
 * One second of mono PCM16, or the device minimum if larger. Each chunk is also written to the draft on the
 * reading thread, so a storage stall up to this long fills the buffer instead of dropping samples.
 */
internal fun chooseCaptureBufferBytes(sampleRate: Int, minBufferBytes: Int): Int =
    maxOf(minBufferBytes, sampleRate * Short.SIZE_BYTES)

/** Records one mono PCM16 take from the microphone through [readTake]. */
class TakeRecorder(private val context: Context) {
    /** Blocking; call on an IO thread. Throws [IllegalStateException] with a user message if it cannot record. */
    fun record(
        openDraft: (sampleRate: Int) -> RecordingDraft?,
        shouldStop: () -> Boolean,
        onProgress: (elapsedMs: Long) -> Unit,
    ): RecordedTake {
        // The screen asks first; this guards any other caller.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(MICROPHONE_DENIED_MESSAGE)
        }
        val record = createAudioRecord() ?: throw IllegalStateException(MICROPHONE_UNAVAILABLE_MESSAGE)
        val silenced = AtomicBoolean(false)
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                    ?.let { silenced.set(it.isClientSilenced) }
            }
        }
        try {
            // Registered before starting, so silencing that applies from the first frame is not missed.
            record.registerAudioRecordingCallback(context.mainExecutor, callback)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { MICROPHONE_UNAVAILABLE_MESSAGE }
            if (record.activeRecordingConfiguration?.isClientSilenced == true) silenced.set(true)
            val rate = record.sampleRate
            return readTake(
                sampleRate = rate,
                maxFrames = AUDIO_MAX_DURATION_SECONDS * rate,
                chunkFrames = rate / 50,
                read = { buffer, offset, count -> record.read(buffer, offset, count) },
                readAvailable = { buffer, offset, count ->
                    record.read(buffer, offset, count, AudioRecord.READ_NON_BLOCKING)
                },
                shouldStop = shouldStop,
                isSilenced = silenced::get,
                draft = openDraft(rate),
                onProgress = onProgress,
            )
        } finally {
            record.unregisterAudioRecordingCallback(callback)
            runCatching { record.stop() }
            record.release()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord? {
        for (rate in RECORDING_SAMPLE_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    chooseCaptureBufferBytes(rate, minBuffer),
                )
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }
}
