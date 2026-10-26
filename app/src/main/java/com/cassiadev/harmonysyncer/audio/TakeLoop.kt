package com.cassiadev.harmonysyncer.audio

private const val PROGRESS_STEP_MS = 100L

enum class TakeStopReason { Requested, TimeLimit, Interrupted }

/** A finished take; [draft] is still owned by the caller, who deletes it once saved and releases it. */
class RecordedTake(val clip: PcmClip, val stopReason: TakeStopReason, val draft: RecordingDraft?)

/**
 * Reads microphone chunks through [read] into one buffer and into [draft] until [shouldStop] or
 * [isSilenced] turns true, [maxFrames] fill the buffer, or [read] fails. A failure before the first
 * frame means the microphone never delivered audio: the draft is removed and nothing is returned.
 */
fun readTake(
    sampleRate: Int,
    maxFrames: Int,
    chunkFrames: Int,
    read: (buffer: ShortArray, offset: Int, count: Int) -> Int,
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
    try {
        val reason = run {
            while (frames < maxFrames) {
                if (shouldStop()) return@run TakeStopReason.Requested
                if (isSilenced()) return@run TakeStopReason.Interrupted
                val count = read(buffer, frames, minOf(chunkFrames, maxFrames - frames))
                if (count < 0) {
                    if (frames > 0) return@run TakeStopReason.Interrupted
                    draft?.delete()
                    draft?.release()
                    throw IllegalStateException(MICROPHONE_UNAVAILABLE_MESSAGE)
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
