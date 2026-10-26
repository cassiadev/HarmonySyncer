package com.cassiadev.harmonysyncer.audio

// No android.* or java.*: this file is meant to move to shared KMP code unchanged.

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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

private const val CLICK_AMPLITUDE = 16_384.0
private const val CLICKS_PER_SECOND_OF_LENGTH = 50 // 20 ms clicks
private const val ACCENT_HZ = 1_760.0
private const val BEAT_HZ = 880.0

/** Where the click track stands: beats before [nextBeat] have sounded or were skipped and never come back. */
class ClickCursor {
    var nextBeat = 0L
    /** Clicks still sounding, as (start frame, first beat of a bar). */
    val sounding = ArrayDeque<Pair<Long, Boolean>>()
}

/** One sample of a 20 ms decaying sine click, [offset] frames after it starts. */
internal fun clickSampleAt(offset: Int, accent: Boolean, sampleRate: Int): Int {
    val length = sampleRate / CLICKS_PER_SECOND_OF_LENGTH
    val decay = 1.0 - offset.toDouble() / length
    val hz = if (accent) ACCENT_HZ else BEAT_HZ
    return (CLICK_AMPLITUDE * decay * sin(2 * PI * hz * offset / sampleRate)).roundToInt()
}

/**
 * Fills [out], the output frames from [startFrame], with the clicks of beats placed at
 * `firstClickFrame + clickFrameAt(beat)`. A beat whose start is already behind [startFrame] is skipped;
 * a started click keeps its own start even if [firstClickFrame] moves, and overlapping clicks add up.
 * Muted chunks write silence but still move [cursor] past their beats.
 */
fun renderClickChunk(
    out: ShortArray,
    startFrame: Long,
    firstClickFrame: Long,
    bpm: Int,
    beatsPerBar: Int,
    sampleRate: Int,
    muted: Boolean,
    cursor: ClickCursor,
) {
    out.fill(0)
    val end = startFrame + out.size
    val length = sampleRate / CLICKS_PER_SECOND_OF_LENGTH
    if (muted) cursor.sounding.clear()
    while (true) {
        val start = firstClickFrame + clickFrameAt(cursor.nextBeat, bpm, sampleRate)
        if (start >= end) break
        if (start >= startFrame && !muted) cursor.sounding.addLast(start to (cursor.nextBeat % beatsPerBar == 0L))
        cursor.nextBeat++
    }
    for ((clickStart, accent) in cursor.sounding) {
        for (frame in maxOf(clickStart, startFrame) until minOf(clickStart + length, end)) {
            val index = (frame - startFrame).toInt()
            val sum = out[index] + clickSampleAt((frame - clickStart).toInt(), accent, sampleRate)
            out[index] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
    while (cursor.sounding.isNotEmpty() && cursor.sounding.first().first + length <= end) cursor.sounding.removeFirst()
}

/** The first click goes no earlier than one chunk past what is already written, which cannot change. */
fun chooseFirstClickFrame(writtenFrames: Long, chunkFrames: Int, earliestFrame: Long): Long =
    max(writtenFrames + chunkFrames, earliestFrame)

/** Small clock differences keep the click grid; a jump over 2 ms (a new route, a stall) moves it. */
fun shouldMoveClickFrame(current: Long, measured: Long, sampleRate: Int): Boolean =
    abs(measured - current) * 1_000 > 2L * sampleRate
