package com.cassiadev.harmonysyncer.audio

// No android.* or java.*: this file is meant to move to shared KMP code unchanged.

const val METRONOME_MIN_BPM = 40
const val METRONOME_MAX_BPM = 240
val METRONOME_BEATS_PER_BAR = listOf(2, 3, 4, 6)

/** Time from the moment a count-in is decided to its first beat. */
const val COUNT_IN_LEAD_NANOS = 150_000_000L

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND
private const val TAP_RESET_NANOS = 2 * NANOS_PER_SECOND
private const val TAP_INTERVALS = 4
private const val TIMESTAMP_WAIT_NANOS = NANOS_PER_SECOND

data class MetronomeSettings(
    val useForRecording: Boolean = false,
    val bpm: Int = 120,
    val beatsPerBar: Int = 4,
    val clickOn: Boolean = true,
) {
    init {
        require(bpm in METRONOME_MIN_BPM..METRONOME_MAX_BPM) { "BPM $bpm is outside $METRONOME_MIN_BPM..$METRONOME_MAX_BPM" }
        require(beatsPerBar in METRONOME_BEATS_PER_BAR) { "$beatsPerBar beats per bar is not one of $METRONOME_BEATS_PER_BAR" }
    }
}

/** [frame] was captured or presented at [nanos] on the `System.nanoTime()` clock. */
data class FrameTime(val frame: Long, val nanos: Long)

/** `round(beat × 60 × sampleRate / bpm)` with ties rounding up; exact integers, so no drift over an hour. */
fun clickFrameAt(beat: Long, bpm: Int, sampleRate: Int): Long {
    require(beat >= 0) { "beat $beat is negative" }
    return (2 * beat * 60 * sampleRate + bpm) / (2L * bpm)
}

/** The beat sounding [elapsedNanos] after the first beat; negative before it. */
fun beatAt(elapsedNanos: Long, bpm: Int): Long = (elapsedNanos * bpm).floorDiv(NANOS_PER_MINUTE)

/** The frame at [timeNanos] on the clock that [stamp] ties to frames, rounded half up. */
fun frameAt(timeNanos: Long, stamp: FrameTime, sampleRate: Int): Long =
    stamp.frame + (2 * (timeNanos - stamp.nanos) * sampleRate + NANOS_PER_SECOND).floorDiv(2 * NANOS_PER_SECOND)

/** The time of [frame] on the clock that [stamp] ties to frames, rounded half up. */
fun timeNanosAt(frame: Long, stamp: FrameTime, sampleRate: Int): Long =
    stamp.nanos + (2 * (frame - stamp.frame) * NANOS_PER_SECOND + sampleRate).floorDiv(2L * sampleRate)

/**
 * BPM from tap times: the mean of the last four intervals after the last pause over two seconds,
 * rounded and kept in range. Null until two taps follow that pause.
 */
fun estimateTapBpm(tapNanos: List<Long>): Int? {
    val pause = (1 until tapNanos.size).lastOrNull { tapNanos[it] - tapNanos[it - 1] > TAP_RESET_NANOS } ?: 0
    val taps = tapNanos.subList(pause, tapNanos.size).takeLast(TAP_INTERVALS + 1)
    if (taps.size < 2) return null
    val intervals = taps.size - 1L
    val total = taps.last() - taps.first()
    if (total <= 0) return METRONOME_MAX_BPM
    val bpm = (2 * NANOS_PER_MINUTE * intervals + total) / (2 * total)
    return bpm.coerceIn(METRONOME_MIN_BPM.toLong(), METRONOME_MAX_BPM.toLong()).toInt()
}

/**
 * Waits for an audio timestamp that is really advancing: a read counts once its frame and time both
 * moved past the previous read. After a second without one, [offer] settles for the caller's fallback.
 */
class TimestampWait(private val startNanos: Long) {
    private var previous: FrameTime? = null

    /** The stamp to use and whether it was measured, or null to keep waiting. */
    fun offer(read: FrameTime?, nowNanos: Long, fallback: FrameTime): Pair<FrameTime, Boolean>? {
        if (read != null) {
            val last = previous
            previous = read
            if (last != null && read.frame > last.frame && read.nanos > last.nanos) return read to true
        }
        return if (nowNanos - startNanos >= TIMESTAMP_WAIT_NANOS) fallback to false else null
    }
}
