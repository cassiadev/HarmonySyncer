package com.cassiadev.harmonysyncer.audio

import android.Manifest
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cassiadev.harmonysyncer.MainActivity
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

private const val RATE = 48_000
private const val SOUND_RUN_CHUNKS = 3
private const val TAG = "PlaybackLoopback"
private const val CLIP_SEED = 1L
private const val CONTROL_SEED = 9_999L
private const val STOP_AFTER_MS = 1_500L
private const val STOP_RUNS = 5
private const val LONG_CLIP_SECONDS = 59
private const val LONG_CLIP_RUNS = 3
// First guess: a chunk counts as found when it matches half again better than noise that never played.
private const val CONTROL_MARGIN = 1.5

/**
 * Plays through the real player and records through the real recorder, finding the sound in the take. The
 * measurements make noise and take minutes, so they run only with measurePlayback=true; see the design, section 6.
 */
class PlaybackLoopbackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val noiseClip = buildNoiseClip(CLIP_SEED)
    private val controlClip = buildNoiseClip(CONTROL_SEED)

    private class Capture(val samples: ShortArray, val rate: Int, val firstFrameNanos: Long, val measured: Boolean) {
        fun captureFrameAt(nanos: Long): Int = ((nanos - firstFrameNanos) * rate / 1_000_000_000L).toInt()
    }

    private class Play(val requestNanos: Long, val stopNanos: Long)

    private class PlayResult(val latencyFrames: Int?, val startFrame: Int?, val span: SoundSpan?, val threshold: Double)

    @Test
    fun measuresHowLongSoundLastsAfterStop() = runMeasurement("stop") {
        val player = PcmPreviewPlayer()
        val plays = mutableListOf<Play>()
        val capture = recordWhile {
            repeat(STOP_RUNS) {
                plays += playThenStop(player)
                delay(1_000)
            }
        }
        val file = saveCapture("stop", 1, capture)
        plays.forEachIndexed { index, play ->
            val result = analyzePlay(capture, play.requestNanos, plays.getOrNull(index + 1)?.requestNanos)
            val endFrame = result.span?.let { result.startFrame!! + (it.lastChunk + 1) * capture.rate / 50 }
            val silenceFrames = endFrame?.let { it - capture.captureFrameAt(play.stopNanos) }
            report("stop[$index] $file: stop to silence ${describeTiming(silenceFrames, capture.rate, capture.measured)}, ${describePlay(capture, result)}")
        }
    }

    @Test
    fun measuresReplayRightAfterStop() = runMeasurement("replay") {
        val player = PcmPreviewPlayer()
        val plays = mutableListOf<Play>()
        val capture = recordWhile {
            repeat(STOP_RUNS) { plays += playThenStop(player) }
        }
        val file = saveCapture("replay", 1, capture)
        plays.forEachIndexed { index, play ->
            val result = analyzePlay(capture, play.requestNanos, plays.getOrNull(index + 1)?.requestNanos)
            report("replay[$index] $file: ${describePlay(capture, result)}")
        }
    }

    @Test
    fun measuresFirstPlayAfterIdle() {
        val idleSeconds = arguments.getString("idleSeconds")?.toLong() ?: 180L
        val idleRuns = arguments.getString("idleRuns")?.toInt() ?: 3
        runMeasurement("idle") {
            repeat(idleRuns) { run ->
                Thread.sleep(idleSeconds * 1_000)
                val requestNanos = AtomicLong()
                val capture = recordWhile {
                    requestNanos.set(System.nanoTime())
                    PcmPreviewPlayer().play(PcmClip(noiseClip, RATE))
                }
                val file = saveCapture("idle-${idleSeconds}s", run, capture)
                val result = analyzePlay(capture, requestNanos.get(), null)
                report("idle[$run] $file: after $idleSeconds s, ${describePlay(capture, result)}")
                result.startFrame?.let { start ->
                    val coefficients = findChunkCoefficients(capture.samples, noiseClip, capture.rate, start, capture.samples.size)
                    report("idle[$run] first chunks: ${coefficients.take(25).joinToString { "%.2f".format(it) }}")
                }
            }
        }
    }

    @Test
    fun measuresContinuityOfALongClip() = runMeasurement("continuity") {
        val bursts = List(LONG_CLIP_SECONDS) { buildBurst(1_000L + it) }
        val longClip = ShortArray(LONG_CLIP_SECONDS * RATE).also { samples ->
            bursts.forEachIndexed { index, burst -> burst.copyInto(samples, index * RATE) }
        }
        repeat(LONG_CLIP_RUNS) { run ->
            val before = readUnderrunLines()
            val requestNanos = AtomicLong()
            val capture = recordWhile {
                requestNanos.set(System.nanoTime())
                PcmPreviewPlayer().play(PcmClip(longClip, RATE))
            }
            val after = readUnderrunLines()
            val file = saveCapture("continuity", run, capture)
            val offsets = findBurstOffsets(capture, bursts, capture.captureFrameAt(requestNanos.get()))
            val found = offsets.filterNotNull()
            val largestStep = found.zipWithNext { earlier, later -> abs(later - earlier) }.maxOrNull()
            report(
                "continuity[$run] $file: found ${found.size} of ${bursts.size}, " +
                    "request to microphone ${describeTiming(offsets[1], capture.rate, capture.measured)}, " +
                    "position range ${found.minOrNull()}..${found.maxOrNull()} frames, largest step $largestStep frames",
            )
            report("continuity[$run] frames: ${offsets.joinToString()}")
            report("continuity[$run] underrun lines that changed: ${after.filter { it !in before }}")
        }
    }

    /** Skips outside a measurement run; otherwise keeps the app in front, where the microphone is allowed. */
    private fun runMeasurement(name: String, measure: () -> Unit) {
        assumeTrue(arguments.getString("measurePlayback") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val audio = context.getSystemService(AudioManager::class.java)
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            val volume = "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
            report("$name: outputs $outputs, media volume $volume")
            measure()
        }
    }

    /** Records while [script] runs; the first sample is captured at [Capture.firstFrameNanos]. */
    private fun recordWhile(script: suspend () -> Unit): Capture {
        val anchor = TakeAnchor()
        val firstFrameNanos = AtomicLong()
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val running = thread {
            try {
                val stamp = runBlocking { anchor.stamp.await() }
                val start = System.nanoTime() + COUNT_IN_LEAD_NANOS
                anchor.firstFrame = frameAt(start, stamp.time, stamp.sampleRate)
                firstFrameNanos.set(start)
                while (System.nanoTime() < start + 100_000_000L) Thread.sleep(1)
                runBlocking { script() }
            } catch (thrown: Throwable) {
                failure.set(thrown)
            } finally {
                finished.set(true)
            }
        }
        val recorder = TakeRecorder(context)
        val take = recorder.record(openDraft = { null }, shouldStop = finished::get, onProgress = {}, anchor = anchor)
        running.join()
        failure.get()?.let { throw it }
        val stamp = runBlocking { anchor.stamp.await() }
        check(take.clip.sampleRate == RATE) { "capture rate ${take.clip.sampleRate}" }
        report("capture: ${take.clip.samples.size} frames, stamp measured ${stamp.measured}, input ${recorder.lastRoutedInputType}, stop ${take.stopReason}")
        return Capture(take.clip.samples, take.clip.sampleRate, firstFrameNanos.get(), stamp.measured)
    }

    private suspend fun playThenStop(player: PcmPreviewPlayer): Play = coroutineScope {
        val requestNanos = AtomicLong()
        val playing = launch(Dispatchers.IO) {
            requestNanos.set(System.nanoTime())
            player.play(PcmClip(noiseClip, RATE))
        }
        delay(STOP_AFTER_MS)
        val stopNanos = System.nanoTime()
        player.stop()
        playing.join()
        Play(requestNanos.get(), stopNanos)
    }

    /** Looks only between this request and the next, so one play's sound is never read as another's. */
    private fun analyzePlay(capture: Capture, requestNanos: Long, nextRequestNanos: Long?): PlayResult {
        val rate = capture.rate
        val requestFrame = capture.captureFrameAt(requestNanos)
        val windowEnd = nextRequestNanos?.let(capture::captureFrameAt) ?: capture.samples.size
        val controlChunk = controlClip.copyOfRange(rate, rate + rate / 10)
        val latencyEnd = minOf(requestFrame + rate * 17 / 10, windowEnd)
        val control = findBestMatch(capture.samples, controlChunk, requestFrame + rate - rate / 100, latencyEnd)?.coefficient ?: 0.0
        val latency = findLatencyFrames(capture.samples, noiseClip, rate, requestFrame, windowEnd, control * CONTROL_MARGIN)
            ?: return PlayResult(null, null, null, control * CONTROL_MARGIN)
        val startFrame = requestFrame + latency
        val threshold = findChunkCoefficients(capture.samples, controlClip, rate, startFrame, windowEnd).max() * CONTROL_MARGIN
        return PlayResult(latency, startFrame, findSoundSpan(capture.samples, noiseClip, rate, startFrame, windowEnd, threshold), threshold)
    }

    private fun describePlay(capture: Capture, result: PlayResult): String {
        val chunks = result.span?.let { "chunks ${it.firstChunk}..${it.lastChunk}, start cut ${it.firstChunk * 20} ms" } ?: "no sound span"
        return "request to microphone ${describeTiming(result.latencyFrames, capture.rate, capture.measured)}, $chunks, threshold %.3f".format(result.threshold)
    }

    /** Frames from each burst's place in the clip to where it was captured, or null where it was not found. */
    private fun findBurstOffsets(capture: Capture, bursts: List<ShortArray>, requestFrame: Int): List<Int?> {
        val rate = capture.rate
        val from = requestFrame + rate - rate / 100
        val until = requestFrame + rate * 17 / 10
        val threshold = (findBestMatch(capture.samples, buildBurst(CONTROL_SEED), from, until)?.coefficient ?: 0.0) * CONTROL_MARGIN
        val first = findBestMatch(capture.samples, bursts[1], from, until)?.takeIf { it.coefficient >= threshold }
            ?: return bursts.map { null }
        var expected = first.frame - requestFrame - rate
        val margin = rate * 15 / 100
        return bursts.mapIndexed { index, burst ->
            val place = requestFrame + index * rate
            findBestMatch(capture.samples, burst, place + expected - margin, place + expected + margin)
                ?.takeIf { it.coefficient >= threshold }
                ?.let { match -> (match.frame - place).also { if (index > 0) expected = it } }
        }
    }

    /** Thread and counter lines of the audio service; the counters include every app using that thread. */
    private fun readUnderrunLines(): List<String> {
        val output = instrumentation.uiAutomation.executeShellCommand("dumpsys media.audio_flinger")
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { "Normal mixer raw underrun" in it || "underruns=" in it || it.startsWith("Output thread") }
                .toList()
        }
    }

    private fun saveCapture(name: String, run: Int, capture: Capture): String {
        val file = File(context.getExternalFilesDir("measurements"), "$name-${System.currentTimeMillis()}-$run.wav")
        file.writeBytes(encodeWavPcm16(PcmClip(capture.samples, capture.rate)))
        return file.name
    }

    private fun buildBurst(seed: Long): ShortArray {
        val random = Random(seed)
        return ShortArray(RATE / 50) { (random.nextInt(24_001) - 12_000).toShort() }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    @Test
    fun findsLatencyFromTheOneSecondChunk() {
        val clip = buildNoiseClip(1)

        assertEquals(12_000, findLatencyFrames(buildFakeCapture(clip), clip, RATE, 0, Int.MAX_VALUE, 0.1))
    }

    @Test
    fun reportsACutStart() {
        val clip = buildNoiseClip(1)

        val span = findSoundSpan(buildFakeCapture(clip, soundFrom = RATE * 60 / 1_000), clip, RATE, 12_000, Int.MAX_VALUE, 0.1)

        assertEquals(3, span?.firstChunk)
    }

    @Test
    fun reportsWhereSoundStopped() {
        val clip = buildNoiseClip(1)

        val span = findSoundSpan(buildFakeCapture(clip, soundUntil = RATE * 14 / 10), clip, RATE, 12_000, Int.MAX_VALUE, 0.1)

        assertEquals(69, span?.lastChunk)
    }

    @Test
    fun silenceGivesNoLatency() {
        assertNull(findLatencyFrames(ShortArray(5 * RATE), buildNoiseClip(1), RATE, 0, Int.MAX_VALUE, 0.1))
    }

    @Test
    fun unmeasuredCaptureReportsNoAbsoluteLatency() {
        assertEquals("unmeasured", describeTiming(12_000, RATE, false))
        assertEquals("250.00 ms", describeTiming(12_000, RATE, true))
        assertEquals("not found", describeTiming(null, RATE, true))
    }

    /** The clip at 0.3 gain, 12,000 frames in, over weak unrelated noise. */
    private fun buildFakeCapture(clip: ShortArray, soundFrom: Int = 0, soundUntil: Int = clip.size): ShortArray {
        val noise = Random(77)
        val capture = ShortArray(12_000 + clip.size + RATE) { (noise.nextInt(601) - 300).toShort() }
        for (index in soundFrom until soundUntil) {
            capture[12_000 + index] = (capture[12_000 + index] + clip[index] * 3 / 10).toShort()
        }
        return capture
    }

    private fun buildNoiseClip(seed: Long): ShortArray {
        val random = Random(seed)
        return ShortArray(3 * RATE) { (random.nextInt(24_001) - 12_000).toShort() }
    }
}

internal class ChunkMatch(val frame: Int, val coefficient: Double)

/** The first and last 20 ms chunks of a clip found where they belong in a capture. */
internal class SoundSpan(val firstChunk: Int, val lastChunk: Int)

/** |normalized correlation| of [chunk] with [capture] at [frame]; 0 when it does not fit there. */
internal fun coefficientAt(capture: ShortArray, chunk: ShortArray, frame: Int): Double {
    if (frame < 0 || frame + chunk.size > capture.size) return 0.0
    var product = 0.0
    var captureEnergy = 0.0
    var chunkEnergy = 0.0
    for (index in chunk.indices) {
        val captured = capture[frame + index].toDouble()
        val expected = chunk[index].toDouble()
        product += captured * expected
        captureEnergy += captured * captured
        chunkEnergy += expected * expected
    }
    return if (captureEnergy == 0.0 || chunkEnergy == 0.0) 0.0 else abs(product) / sqrt(captureEnergy * chunkEnergy)
}

internal fun findBestMatch(capture: ShortArray, chunk: ShortArray, from: Int, until: Int): ChunkMatch? {
    var best: ChunkMatch? = null
    for (frame in from.coerceAtLeast(0) until until.coerceAtMost(capture.size - chunk.size + 1)) {
        val coefficient = coefficientAt(capture, chunk, frame)
        if (best == null || coefficient > best.coefficient) best = ChunkMatch(frame, coefficient)
    }
    return best
}

/** Capture frames from the request to clip position 1 s, or null when the chunk there is not found. */
internal fun findLatencyFrames(capture: ShortArray, clip: ShortArray, rate: Int, requestFrame: Int, windowEnd: Int, threshold: Double): Int? {
    val expected = requestFrame + rate
    val chunk = clip.copyOfRange(rate, rate + rate / 10)
    val match = findBestMatch(capture, chunk, expected - rate / 100, minOf(expected + rate * 7 / 10, windowEnd))
    return match?.takeIf { it.coefficient >= threshold }?.let { it.frame - expected }
}

/** The best [coefficientAt] within five frames of where each 20 ms chunk of [clip] belongs after [startFrame]. */
internal fun findChunkCoefficients(capture: ShortArray, clip: ShortArray, rate: Int, startFrame: Int, windowEnd: Int): List<Double> {
    val length = rate / 50
    return (0 until clip.size / length).map { index ->
        val frame = startFrame + index * length
        if (frame + length > windowEnd) return@map 0.0
        val chunk = clip.copyOfRange(index * length, (index + 1) * length)
        (frame - 5..frame + 5).maxOf { coefficientAt(capture, chunk, it) }
    }
}

/** Counts only runs of [SOUND_RUN_CHUNKS] chunks, so one chance match in the noise cannot move either end. */
internal fun findSoundSpan(capture: ShortArray, clip: ShortArray, rate: Int, startFrame: Int, windowEnd: Int, threshold: Double): SoundSpan? {
    val coefficients = findChunkCoefficients(capture, clip, rate, startFrame, windowEnd)
    val runStarts = (0..coefficients.size - SOUND_RUN_CHUNKS).filter { start ->
        (start until start + SOUND_RUN_CHUNKS).all { coefficients[it] >= threshold }
    }
    return if (runStarts.isEmpty()) null else SoundSpan(runStarts.first(), runStarts.last() + SOUND_RUN_CHUNKS - 1)
}

/** A time in frames, unless it came from a fallback capture stamp or was never found. */
internal fun describeTiming(frames: Int?, rate: Int, measured: Boolean): String = when {
    !measured -> "unmeasured"
    frames == null -> "not found"
    else -> "%.2f ms".format(frames * 1_000.0 / rate)
}
