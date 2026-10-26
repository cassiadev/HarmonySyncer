package com.cassiadev.harmonysyncer.audio

private const val PROGRESS_STEP_MS = 100L

enum class TakeStopReason { Requested, TimeLimit, Interrupted }

/**
 * Remembers that a take was silenced at any point. The recording callback may report silenced and then
 * unsilenced before the reading thread looks again; the take must still stop as interrupted.
 */
class SilencingLatch {
    @Volatile var wasSilenced = false
        private set

    fun report(silenced: Boolean) {
        if (silenced) wasSilenced = true
    }
}

/** A finished take; [draft] is still owned by the caller, who deletes it once saved and releases it. */
class RecordedTake(val clip: PcmClip, val stopReason: TakeStopReason, val draft: RecordingDraft?)

/**
 * Reads microphone chunks through [read] into one buffer and into [draft] until [shouldStop] or
 * [isSilenced] turns true, [maxFrames] fill the buffer, or [read] fails. A failure before the first
 * frame means the microphone never delivered audio: the draft is removed and nothing is returned.
 * On a stop request, [readAvailable] (non-blocking) collects audio already captured but not yet read.
 */
fun readTake(
    sampleRate: Int,
    maxFrames: Int,
    chunkFrames: Int,
    read: (buffer: ShortArray, offset: Int, count: Int) -> Int,
    readAvailable: (buffer: ShortArray, offset: Int, count: Int) -> Int,
    shouldStop: () -> Boolean,
    isSilenced: () -> Boolean,
    draft: RecordingDraft?,
    onProgress: (elapsedMs: Long) -> Unit,
): RecordedTake {
    val buffer = ShortArray(maxFrames)
    var frames = 0
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
                if (shouldStop()) {
                    // A slow draft write may leave much of the time before the stop in the microphone buffer.
                    val tail = readAvailable(buffer, frames, maxFrames - frames)
                    if (tail > 0) {
                        draft?.append(buffer, frames, tail)
                        frames += tail
                    }
                    return@run TakeStopReason.Requested
                }
                // Silenced from the start usually means another app holds the microphone (Android 10+).
                if (isSilenced()) {
                    if (frames > 0) return@run TakeStopReason.Interrupted
                    failWithoutAudio()
                }
                val count = read(buffer, frames, minOf(chunkFrames, maxFrames - frames))
                if (count < 0) {
                    if (frames > 0) return@run TakeStopReason.Interrupted
                    failWithoutAudio()
                }
                // A failed draft write leaves the take in memory only; recording goes on.
                draft?.append(buffer, frames, count)
                frames += count
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
