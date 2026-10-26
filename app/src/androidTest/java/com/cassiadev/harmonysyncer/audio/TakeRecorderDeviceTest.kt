package com.cassiadev.harmonysyncer.audio

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cassiadev.harmonysyncer.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
