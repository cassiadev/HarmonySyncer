package com.cassiadev.harmonysyncer.audio

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cassiadev.harmonysyncer.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Records from the real microphone; the activity keeps the app in the foreground, where capture is allowed. */
class TakeRecorderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun recordsAboutOneSecond() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val directory = File(context.cacheDir, "take-test-${System.nanoTime()}")
        val drafts = RecordingDrafts(directory)
        ActivityScenario.launch(MainActivity::class.java).use {
            // Timed from the first delivered chunk: microphone start-up can take most of a second on first use.
            var firstAudioAt = 0L
            val take = TakeRecorder(context).record(
                openDraft = { rate -> drafts.create("take", rate) },
                shouldStop = { firstAudioAt != 0L && System.nanoTime() - firstAudioAt > 1_000_000_000L },
                onProgress = { if (firstAudioAt == 0L) firstAudioAt = System.nanoTime() },
            )
            try {
                assertEquals(TakeStopReason.Requested, take.stopReason)
                val rate = take.clip.sampleRate
                assertTrue("rate $rate", rate == 48_000 || rate == 44_100)
                val frames = take.clip.samples.size
                assertTrue("frames $frames", frames in (rate * 7 / 10)..(rate * 3 / 2))
                assertEquals(frames.toLong() * Short.SIZE_BYTES, File(directory, "take@$rate.pcm").length())
            } finally {
                take.draft?.delete()
                take.draft?.release()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun trimsToTheAnchorAndReportsATimestamp() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            val anchor = TakeAnchor()
            val countIn = AtomicLong(0)
            // Decides the count-in from the capture stamp, as the metronome take does.
            val decider = thread {
                val stamp = runBlocking { anchor.stamp.await() }
                val start = System.nanoTime() + COUNT_IN_LEAD_NANOS
                anchor.firstFrame = frameAt(start, stamp.time, stamp.sampleRate)
                countIn.set(start)
            }
            val take = TakeRecorder(context).record(
                openDraft = { null },
                shouldStop = { countIn.get() != 0L && System.nanoTime() - countIn.get() > 1_000_000_000L },
                onProgress = {},
                anchor = anchor,
            )
            decider.join()

            val stamp = runBlocking { anchor.stamp.await() }
            assertTrue("capture timestamp was not measured", stamp.measured)
            val rate = take.clip.sampleRate
            val frames = take.clip.samples.size
            assertTrue("frames $frames at $rate Hz", frames in (rate * 9 / 10)..(rate * 11 / 10))
        }
    }

    /** A wired headset's microphone sits next to its earbuds and would pick up the metronome clicks. */
    @Test
    fun recordsFromTheBuiltInMicWithAHeadsetPluggedIn() {
        val inputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
        assumeTrue("needs a wired headset with a microphone", inputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET })
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            val recorder = TakeRecorder(context)
            val start = System.nanoTime()
            val take = recorder.record(openDraft = { null }, shouldStop = { System.nanoTime() - start > 1_000_000_000L }, onProgress = {})

            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, recorder.lastRoutedInputType)
            // The input check must not stop a take that stayed on the built-in microphone.
            assertEquals(TakeStopReason.Requested, take.stopReason)
        }
    }
}
