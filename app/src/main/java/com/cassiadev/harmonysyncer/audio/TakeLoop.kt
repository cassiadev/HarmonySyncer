package com.cassiadev.harmonysyncer.audio

import kotlinx.coroutines.CompletableDeferred

private const val PROGRESS_STEP_MS = 100L

enum class TakeStopReason { Requested, TimeLimit, Interrupted }

/**
 * Remembers that a take was interrupted (silenced, or moved off its microphone) at any point. The recording callback may report silenced and then
 * unsilenced before the reading thread looks again; the take must still stop as interrupted.
 */
class TakeInterruptionLatch {
    @Volatile var wasInterrupted = false
        private set

    fun report(interrupted: Boolean) {
        if (interrupted) wasInterrupted = true
    }
}

/** A finished take; [draft] is still owned by the caller, who deletes it once saved and releases it. */
class RecordedTake(val clip: PcmClip, val stopReason: TakeStopReason, val draft: RecordingDraft?)

/** Ties the capture's input frames to the `System.nanoTime()` clock; [measured] is false for the fallback guess. */
data class CaptureStamp(val sampleRate: Int, val time: FrameTime, val measured: Boolean)

/**
 * Lets a metronome take start at its count-in: the recorder completes [stamp] once, and the caller then sets
 * [firstFrame], the input frame that becomes the take's first sample.
 */
class TakeAnchor {
    val stamp = CompletableDeferred<CaptureStamp>()
    @Volatile var firstFrame: Long? = null
}

/**
 * Reads microphone chunks through [read] into one buffer and into [draft] until [shouldStop] or
 * [isInterrupted] turns true, [maxFrames] fill the buffer, or [read] fails. A failure before the first
 * frame means the microphone never delivered audio: the draft is removed and nothing is returned.
 * On a stop request, [readAvailable] (non-blocking) collects audio already captured but not yet read.
 * Input frames before [firstFrame] (all of them while it is null) are dropped from the buffer and the draft;
 * [onRead] gets the count of input frames after each successful read.
 */
fun readTake(
    sampleRate: Int,
    maxFrames: Int,
    chunkFrames: Int,
    read: (buffer: ShortArray, offset: Int, count: Int) -> Int,
    readAvailable: (buffer: ShortArray, offset: Int, count: Int) -> Int,
    shouldStop: () -> Boolean,
    isInterrupted: () -> Boolean,
    draft: RecordingDraft?,
    onProgress: (elapsedMs: Long) -> Unit,
    firstFrame: () -> Long? = { 0L },
    onRead: (inputFrames: Long) -> Unit = {},
): RecordedTake {
    val buffer = ShortArray(maxFrames)
    var frames = 0
    // Frames delivered by the microphone, kept or not; decides whether it ever gave audio.
    var inputFrames = 0L
    /** Moves the kept part of [count] frames just read at [frames] into place and returns its size. */
    fun keep(count: Int): Int {
        val before = inputFrames
        inputFrames += count
        onRead(inputFrames)
        val first = firstFrame()
        val drop = if (first == null) count else (first - before).coerceIn(0L, count.toLong()).toInt()
        if (drop in 1 until count) buffer.copyInto(buffer, frames, frames + drop, frames + count)
        return count - drop
    }
    // Not -1: integer division puts -1 in the same 100 ms step as 0..99, which would hide the first chunk.
    var lastReportedMs = Long.MIN_VALUE
    fun report(force: Boolean) {
        val elapsedMs = frames * 1_000L / sampleRate
        if (elapsedMs != lastReportedMs && (force || elapsedMs / PROGRESS_STEP_MS != lastReportedMs / PROGRESS_STEP_MS)) {
            lastReportedMs = elapsedMs
            onProgress(elapsedMs)
        }
    }
    fun failWithoutAudio(): Nothing {
        draft?.delete()
        draft?.release()
        throw IllegalStateException(MICROPHONE_UNAVAILABLE_MESSAGE)
    }
    try {
        val reason = run {
            while (frames < maxFrames) {
                // Checked before a stop request: a stopped take that was silenced from the start must not save
                // the silenced samples left in the buffer. Silenced from the start usually means another app holds
                // the microphone (Android 10+).
                if (isInterrupted()) {
                    if (inputFrames > 0) return@run TakeStopReason.Interrupted
                    failWithoutAudio()
                }
                if (shouldStop()) {
                    // A slow draft write may leave much of the time before the stop in the microphone buffer.
                    val tail = readAvailable(buffer, frames, maxFrames - frames)
                    if (tail > 0) {
                        val kept = keep(tail)
                        draft?.append(buffer, frames, kept)
                        frames += kept
                    }
                    return@run TakeStopReason.Requested
                }
                val count = read(buffer, frames, minOf(chunkFrames, maxFrames - frames))
                if (count < 0) {
                    if (inputFrames > 0) return@run TakeStopReason.Interrupted
                    failWithoutAudio()
                }
                val kept = keep(count)
                // A failed draft write leaves the take in memory only; recording goes on.
                draft?.append(buffer, frames, kept)
                frames += kept
                report(force = false)
            }
            TakeStopReason.TimeLimit
        }
        report(force = true)
        return RecordedTake(PcmClip(buffer.copyOf(frames), sampleRate), reason, draft)
    } finally {
        draft?.close()
    }
}
