package com.cassiadev.harmonysyncer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat

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
    // Read by device tests only, like ClickTrackPlayer's routing fields.
    @VisibleForTesting
    @Volatile internal var lastRoutedInputType: Int? = null
        private set

    /**
     * Blocking; call on an IO thread. Throws [IllegalStateException] with a user message if it cannot record.
     * With an [anchor], completes its stamp from the capture timestamp and keeps only frames from its first frame.
     */
    fun record(
        openDraft: (sampleRate: Int) -> RecordingDraft?,
        shouldStop: () -> Boolean,
        onProgress: (elapsedMs: Long) -> Unit,
        anchor: TakeAnchor? = null,
    ): RecordedTake {
        // The screen asks first; this guards any other caller.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(MICROPHONE_DENIED_MESSAGE)
        }
        val record = createAudioRecord() ?: throw IllegalStateException(MICROPHONE_UNAVAILABLE_MESSAGE)
        // A new latch per take, so silencing in one take never stops the next.
        val silenced = SilencingLatch()
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                    ?.let { silenced.report(it.isClientSilenced) }
            }
        }
        try {
            // Registered before starting, so silencing that applies from the first frame is not missed.
            record.registerAudioRecordingCallback(context.mainExecutor, callback)
            // A headset microphone sits next to its earbuds and records the metronome clicks; the phone's own does not.
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { record.setPreferredDevice(it) }
            lastRoutedInputType = null
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { MICROPHONE_UNAVAILABLE_MESSAGE }
            record.activeRecordingConfiguration?.let { silenced.report(it.isClientSilenced) }
            val rate = record.sampleRate
            val timestamp = AudioTimestamp()
            var wait: TimestampWait? = null
            var firstRead: FrameTime? = null
            return readTake(
                sampleRate = rate,
                maxFrames = AUDIO_MAX_DURATION_SECONDS * rate,
                chunkFrames = rate / 50,
                read = { buffer, offset, count -> record.read(buffer, offset, count) },
                readAvailable = { buffer, offset, count ->
                    record.read(buffer, offset, count, AudioRecord.READ_NON_BLOCKING)
                },
                shouldStop = shouldStop,
                isSilenced = { silenced.wasSilenced },
                draft = openDraft(rate),
                onProgress = onProgress,
                firstFrame = if (anchor == null) ({ 0L }) else ({ anchor.firstFrame }),
                onRead = onRead@{ inputFrames ->
                    if (lastRoutedInputType == null) lastRoutedInputType = record.routedDevice?.type
                    if (anchor == null || anchor.stamp.isCompleted) return@onRead
                    val now = System.nanoTime()
                    // Fallback: the newest frame read was captured about when the first read returned.
                    val fallback = firstRead ?: FrameTime(inputFrames, now).also { firstRead = it }
                    val reading = wait ?: TimestampWait(now).also { wait = it }
                    val read = if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        FrameTime(timestamp.framePosition, timestamp.nanoTime)
                    } else {
                        null
                    }
                    reading.offer(read, now, fallback)?.let { (stamp, measured) ->
                        anchor.stamp.complete(CaptureStamp(rate, stamp, measured))
                    }
                },
            )
        } finally {
            // Either step can throw when the audio service is gone; the microphone must still be released.
            runCatching { record.unregisterAudioRecordingCallback(callback) }
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
