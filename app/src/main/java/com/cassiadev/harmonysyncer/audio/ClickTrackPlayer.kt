package com.cassiadev.harmonysyncer.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.annotation.VisibleForTesting

private const val CLICK_TRACK_FAILED_MESSAGE = "이 기기에서 클릭음을 재생할 수 없습니다."
private const val RESYNC_NANOS = 500_000_000L

/**
 * Earphone-like outputs that do not reach the microphone. Classic Bluetooth (A2DP) is allowed although Android
 * cannot tell its earphones from its speakers (design 2).
 */
// The LE Audio types are API 31; older devices never report them.
@SuppressLint("InlinedApi")
internal fun allowsRecordingClick(deviceType: Int?): Boolean = when (deviceType) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    -> true
    else -> false
}

/** Streams metronome clicks whose beats sit at a count-in time on the `System.nanoTime()` clock. */
class ClickTrackPlayer {
    @Volatile private var pendingRestart: MetronomeSettings? = null
    // Set on the main looper by the routing listener, read by the writing thread.
    @Volatile private var routingChanged = false

    // Read by device tests only, like the controller's hasNormalizedClips.
    @VisibleForTesting
    @Volatile internal var lastRoutedDeviceType: Int? = null
        private set

    @VisibleForTesting
    @Volatile internal var lastChunkMuted = false
        private set

    /** Starts the beats again from a new count-in with [settings], keeping the track. */
    fun restart(settings: MetronomeSettings) {
        pendingRestart = settings
    }

    /**
     * Blocking; call on an IO thread. Once [isArmed] and the track's timestamp is usable (or a second has passed),
     * chooses the count-in time T on an unwritten frame and reports it through [onCountInStart] with whether the
     * timestamp was measured. While [recording], outputs other than [allowsRecordingClick] get silence.
     * Stops only through [shouldStop]; throws [IllegalStateException] if the device cannot play the track.
     */
    fun play(
        settings: MetronomeSettings,
        recording: Boolean,
        isArmed: () -> Boolean,
        shouldStop: () -> Boolean,
        onCountInStart: (nanos: Long, measured: Boolean) -> Unit,
    ) {
        pendingRestart = null
        val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val chunk = rate / 100
        val track = buildTrack(rate, chunk)
        routingChanged = false
        val listener = AudioRouting.OnRoutingChangedListener { routingChanged = true }
        try {
            track.addOnRoutingChangedListener(listener, null)
            track.play()
            val startNanos = System.nanoTime()
            val wait = TimestampWait(startNanos)
            val timestamp = AudioTimestamp()
            fun fetchTimestamp() = if (track.getTimestamp(timestamp)) FrameTime(timestamp.framePosition, timestamp.nanoTime) else null

            val out = ShortArray(chunk)
            var current = settings
            var cursor = ClickCursor()
            var stamp: FrameTime? = null
            var measured = false
            var lastRead: FrameTime? = null
            var countInNanos: Long? = null
            var firstClickFrame = 0L
            var lastResync = startNanos
            val settle = ClickGridSettle()
            var written = 0L
            while (!shouldStop()) {
                pendingRestart?.let {
                    pendingRestart = null
                    current = it
                    cursor = ClickCursor()
                    countInNanos = null
                }
                val now = System.nanoTime()
                settle.endIfTooLong(now)
                if (stamp == null) {
                    wait.offer(fetchTimestamp(), now, FrameTime(0, startNanos))?.let { (found, isMeasured) ->
                        stamp = found
                        measured = isMeasured
                        lastRead = found.takeIf { isMeasured }
                    }
                }
                val tied = stamp
                val countIn = countInNanos
                if (countIn == null && tied != null && isArmed()) {
                    firstClickFrame = chooseFirstClickFrame(written, chunk, frameAt(now + COUNT_IN_LEAD_NANOS, tied, rate))
                    val start = timeNanosAt(firstClickFrame, tied, rate)
                    countInNanos = start
                    lastResync = now
                    onCountInStart(start, measured)
                } else if (countIn != null && (routingChanged || settle.settling || now - lastResync >= RESYNC_NANOS)) {
                    if (routingChanged) settle.startSettling(now)
                    routingChanged = false
                    lastResync = now
                    // Only a timestamp that moved on since the last one is trusted, as at the start.
                    val fresh = fetchTimestamp()
                    val previous = lastRead
                    if (fresh != null && (previous == null || (fresh.frame > previous.frame && fresh.nanos > previous.nanos))) {
                        if (previous != null) {
                            val measuredFrame = frameAt(countIn, fresh, rate)
                            firstClickFrame = settle.onMeasured(firstClickFrame, measuredFrame, rate, now)
                        }
                        lastRead = fresh
                    }
                }
                // Quiet while the timestamps settle, so no click lands off the beat (see ClickGridSettle).
                val muted = (recording && !allowsRecordingClick(track.routedDevice?.type.also { lastRoutedDeviceType = it })) || settle.settling
                lastChunkMuted = muted
                if (countInNanos == null) {
                    out.fill(0)
                } else {
                    renderClickChunk(out, written, firstClickFrame, current.bpm, current.beatsPerBar, rate, muted, cursor)
                }
                var offset = 0
                while (offset < chunk) {
                    val count = track.write(out, offset, chunk - offset, AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { CLICK_TRACK_FAILED_MESSAGE }
                    offset += count
                }
                written += chunk
            }
        } finally {
            // Each step can throw when the audio service is gone; the track must still be released.
            runCatching { track.removeOnRoutingChangedListener(listener) }
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun buildTrack(rate: Int, chunk: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { CLICK_TRACK_FAILED_MESSAGE }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Small, so a mute or a moved click reaches the output within a few chunks.
                .setBufferSizeInBytes(maxOf(minBuffer, 2 * chunk * Short.SIZE_BYTES))
                .build()
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException(CLICK_TRACK_FAILED_MESSAGE, failure)
        } catch (failure: UnsupportedOperationException) {
            throw IllegalStateException(CLICK_TRACK_FAILED_MESSAGE, failure)
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException(CLICK_TRACK_FAILED_MESSAGE)
        }
        return track
    }
}
