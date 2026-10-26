package com.cassiadev.harmonysyncer.audio

import android.Manifest
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cassiadev.harmonysyncer.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val BPM = 120
// The next bar's first beat: an accent like the count-in's, away from the take's first frame where the trim is.
private const val MEASURED_BEAT = 4L

/**
 * Measures whether the count-in lands on the same sample in every take (design 9). The clicks play from the
 * speaker into the microphone, so run it somewhere quiet with the media volume at 70% or more and no earphones.
 */
class CountInLatencyDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun grantMicrophone() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private class Take(val take: RecordedTake, val captureMeasured: Boolean, val clickMeasured: Boolean)

    /** Records 2.5 s from the count-in; speaker clicks are allowed here only, through `recording = false`. */
    private fun recordCountIn(withClicks: Boolean): Take {
        val countIn = AtomicLong(0)
        val captureMeasured = AtomicBoolean(false)
        val clickMeasured = AtomicBoolean(false)
        // IO: the recorder blocks its thread while the count-in is decided on another.
        val take = runBlocking(Dispatchers.IO) {
            recordWithCountIn(
                clickDispatcher = Dispatchers.IO,
                recordTake = { anchor ->
                    val recorded = TakeRecorder(context).record(
                        openDraft = { null },
                        shouldStop = { countIn.get() != 0L && System.nanoTime() - countIn.get() > 2_500_000_000L },
                        onProgress = {},
                        anchor = anchor,
                    )
                    if (anchor.stamp.isCompleted) captureMeasured.set(anchor.stamp.await().measured)
                    recorded
                },
                playClicks = if (withClicks) {
                    { isArmed, shouldStop, onCountInStart ->
                        ClickTrackPlayer().play(MetronomeSettings(useForRecording = true, bpm = BPM), false, isArmed, shouldStop) { nanos, measured ->
                            clickMeasured.set(measured)
                            onCountInStart(nanos)
                        }
                    }
                } else {
                    null
                },
                nanoTime = System::nanoTime,
            ) { countIn.set(it) }
        }
        return Take(take, captureMeasured.get(), clickMeasured.get())
    }

    @Test
    fun countInClickLandsOnTheSameSampleEveryTake() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val offsetsMs = (1..5).map {
                val result = recordCountIn(withClicks = true)
                val clip = result.take.clip
                assertTrue("take $it: capture timestamp was not measured", result.captureMeasured)
                assertTrue("take $it: click timestamp was not measured", result.clickMeasured)
                val expected = clickFrameAt(MEASURED_BEAT, BPM, clip.sampleRate).toInt()
                val onset = findClickOnset(clip.samples, clip.sampleRate, expected)
                assertTrue("take $it: no click found near frame $expected", onset != null)
                (onset!! - expected) * 1_000.0 / clip.sampleRate
            }
            val spread = offsetsMs.max() - offsetsMs.min()
            val report = "offsets ms ${offsetsMs.map { "%.2f".format(it) }}, spread %.2f ms".format(spread)
            Log.i("CountInLatency", report)
            println("CountInLatency: $report")
        }
    }

    @Test
    fun silentTakeHasNoClick() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val clip = recordCountIn(withClicks = false).take.clip
            assertNull(findClickOnset(clip.samples, clip.sampleRate, clickFrameAt(MEASURED_BEAT, BPM, clip.sampleRate).toInt()))
        }
    }
}

/**
 * Finds a recorded accent click expected near [expectedFrame]: the window from 50 ms before to 200 ms after it
 * must peak at 8× or more the RMS of the 250 ms before it, and the 20 ms from the first sample over 30% of that
 * peak must cross zero about as often as a 1 760 Hz tone (±25%). Null otherwise. Thresholds are first guesses.
 */
internal fun findClickOnset(samples: ShortArray, sampleRate: Int, expectedFrame: Int): Int? {
    val ms = sampleRate / 1_000
    val noise = (expectedFrame - 300 * ms) until (expectedFrame - 50 * ms)
    val window = (expectedFrame - 50 * ms) until minOf(expectedFrame + 200 * ms, samples.size)
    if (noise.first < 0 || window.isEmpty()) return null
    val rms = sqrt(noise.sumOf { samples[it].toDouble() * samples[it] } / noise.count())
    val peak = window.maxOf { abs(samples[it].toInt()) }
    if (peak < 8 * rms) return null
    val onset = window.first { abs(samples[it].toInt()) >= 0.3 * peak }
    val length = sampleRate / 50
    if (onset + length > samples.size) return null
    val crossings = (onset + 1 until onset + length).count { (samples[it - 1] < 0) != (samples[it] < 0) }
    val expected = 2 * 1_760 * length / sampleRate
    return onset.takeIf { crossings in (expected * 3 / 4)..(expected * 5 / 4) }
}
