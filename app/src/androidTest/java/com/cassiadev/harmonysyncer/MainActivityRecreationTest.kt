package com.cassiadev.harmonysyncer

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.cassiadev.harmonysyncer.audio.AudioPreviewController
import com.cassiadev.harmonysyncer.audio.LoadedAudioFile
import com.cassiadev.harmonysyncer.audio.MIX_EXPORTED_NOTICE
import com.cassiadev.harmonysyncer.audio.MetronomeSettings
import com.cassiadev.harmonysyncer.audio.PcmClip
import com.cassiadev.harmonysyncer.audio.RECORDING_INTERRUPTED_NOTICE
import com.cassiadev.harmonysyncer.audio.RecordedTake
import com.cassiadev.harmonysyncer.audio.TakeStopReason
import com.cassiadev.harmonysyncer.audio.buildWavBytes
import com.cassiadev.harmonysyncer.ui.AudioPreviewTags
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Runs the real activity so configuration changes go through the actual lifecycle. */
@OptIn(ExperimentalTestApi::class)
class MainActivityRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val realController = AudioPreviewViewModel.createController
    private lateinit var wav: File

    @Before
    fun stubFilePicker() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        wav = File.createTempFile("recreation-", ".wav", context.cacheDir)
        wav.writeBytes(buildWavBytes(ShortArray(4_800), 48_000))
        Intents.init()
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(wav))),
        )
    }

    @After
    fun releaseFilePicker() {
        AudioPreviewViewModel.createController = realController
        Intents.release()
        wav.delete()
    }

    @Test
    fun selectedFilesAndSettingsSurviveRecreation() {
        launch().use { scenario ->
            selectBoth(wav.name)
            compose.onNodeWithTag(AudioPreviewTags.VOLUME_B).performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
            compose.onNodeWithTag(AudioPreviewTags.OFFSET)
                .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
            Espresso.pressBack()

            scenario.recreate()

            compose.onNodeWithTag(AudioPreviewTags.NAME_A).assertTextEquals(wav.name)
            compose.onNodeWithTag(AudioPreviewTags.NAME_B).assertTextEquals(wav.name)
            compose.onNodeWithTag(AudioPreviewTags.VOLUME_B)
                .assert(hasStateDescription(context.getString(R.string.volume_value, 0)))
            compose.onNodeWithTag(AudioPreviewTags.TIMING)
                .assertTextContains(context.getString(R.string.offset_value, "+100"))
            compose.onNodeWithTag(AudioPreviewTags.PLAY).performScrollTo().assertIsEnabled()
        }
    }

    @Test
    fun openTimingSheetSurvivesRecreation() {
        launch().use { scenario ->
            selectBoth(wav.name)
            compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
            compose.onNodeWithTag(AudioPreviewTags.OFFSET)
                .performSemanticsAction(SemanticsActions.SetProgress) { it(-40f) }

            scenario.recreate()

            val offset = context.getString(R.string.offset_value, "-40")
            compose.onNodeWithTag(AudioPreviewTags.OFFSET).assert(hasStateDescription(offset))
            Espresso.pressBack()
            compose.onNodeWithTag(AudioPreviewTags.TIMING).assertTextContains(offset)
        }
    }

    @Test
    fun windowTitleIsTheSpokenAppName() {
        // TalkBack announces the window title when the app opens; the screen shows no title bar.
        launch().use { scenario ->
            scenario.onActivity { assertEquals("하모니싱커", it.title.toString()) }
        }
    }

    @Test
    fun loadingContinuesAcrossRecreation() {
        val loading = CompletableDeferred<LoadedAudioFile>()
        val loads = AtomicInteger()
        useFakeController(loadFile = { loads.incrementAndGet(); loading.await() })
        launch().use { scenario ->
            compose.onNodeWithTag(AudioPreviewTags.SELECT_A).performScrollTo().performClick()
            compose.waitUntil(5_000) { loads.get() == 1 }
            // Locked controls, not status copy, show the load is still running.
            val locked = hasTestTag(AudioPreviewTags.SELECT_B) and isNotEnabled()
            compose.waitUntilExactlyOneExists(locked, 5_000)

            scenario.recreate()

            compose.waitUntilExactlyOneExists(locked, 5_000)
            loading.complete(LoadedAudioFile("late.wav", buildSilence()))
            compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.NAME_A) and hasText("late.wav"), 5_000)
            compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.SELECT_B) and isEnabled(), 5_000)
            assertEquals(1, loads.get())
        }
    }

    @Test
    fun playbackStopsOnRecreationAndCanRestart() {
        val plays = AtomicInteger()
        val stops = AtomicInteger()
        useFakeController(
            playPreview = { plays.incrementAndGet(); awaitCancellation() },
            stopPlayback = { stops.incrementAndGet() },
        )
        launch().use { scenario ->
            selectBoth(FAKE_NAME)
            startPlayback()

            scenario.recreate()

            compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.PLAY) and isEnabled(), 5_000)
            // Stopped: the main button is the play button again.
            compose.onNodeWithTag(AudioPreviewTags.STOP).assertDoesNotExist()
            assertEquals(1, stops.get())
            compose.onNodeWithTag(AudioPreviewTags.NAME_B).assertTextEquals(FAKE_NAME)
            startPlayback()
            assertEquals(2, plays.get())
        }
    }

    @Test
    fun playbackStopsInTheBackground() {
        val stops = AtomicInteger()
        useFakeController(playPreview = { awaitCancellation() }, stopPlayback = { stops.incrementAndGet() })
        launch().use { scenario ->
            selectBoth(FAKE_NAME)
            startPlayback()

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.PLAY) and isEnabled(), 5_000)
            assertEquals(1, stops.get())
        }
    }

    @Test
    fun controllerClosesOnlyWhenTheScreenFinishes() {
        val closes = AtomicInteger()
        useFakeController(closePlayback = { closes.incrementAndGet() })
        launch().use { scenario ->
            scenario.recreate()
            assertEquals(0, closes.get())

            scenario.close()
            assertEquals(1, closes.get())
        }
    }


    @Test
    fun recordingContinuesAcrossRecreation() {
        val saves = AtomicInteger()
        useFakeController(recordTake = ::recordUntilStopped, saveRecording = { _, _ -> saves.incrementAndGet() })
        launch().use { scenario ->
            compose.onNodeWithTag(AudioPreviewTags.RECORD_A).performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.RECORDING_TIME), 5_000)

            scenario.recreate()

            compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.RECORDING_TIME), 5_000)
            compose.onNodeWithTag(AudioPreviewTags.STOP_RECORDING).performClick()
            compose.waitUntilExactlyOneExists(
                hasTestTag(AudioPreviewTags.NAME_A) and hasText("HarmonySyncer-A-", substring = true),
                5_000,
            )
            assertEquals(1, saves.get())
        }
    }

    @Test
    fun leavingTheScreenSavesTheRecording() {
        val saves = AtomicInteger()
        useFakeController(recordTake = ::recordUntilStopped, saveRecording = { _, _ -> saves.incrementAndGet() })
        launch().use { scenario ->
            compose.onNodeWithTag(AudioPreviewTags.RECORD_A).performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.RECORDING_TIME), 5_000)

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            compose.waitUntilExactlyOneExists(hasText(RECORDING_INTERRUPTED_NOTICE), 5_000)
            assertEquals(1, saves.get())
        }
    }

    @Test
    fun exportContinuesAcrossRecreationAndTheBackground() {
        val saves = AtomicInteger()
        val writing = CompletableDeferred<Unit>()
        useFakeController(saveRecording = { _, _ ->
            writing.await()
            saves.incrementAndGet()
        })
        launch().use { scenario ->
            selectBoth(FAKE_NAME)
            compose.onNodeWithTag(AudioPreviewTags.EXPORT).performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasText(EXPORTING_STATUS), 5_000)

            // Rotating and going home both call stop(), which must leave the save running.
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntilExactlyOneExists(hasText(EXPORTING_STATUS), 5_000)
            writing.complete(Unit)

            compose.waitUntilExactlyOneExists(hasText(MIX_EXPORTED_NOTICE), 5_000)
            assertEquals(1, saves.get())
        }
    }

    /** Polls like the real recorder and returns a short take once the controller asks it to stop. */
    private suspend fun recordUntilStopped(baseName: String, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): RecordedTake {
        onProgress(0)
        while (!shouldStop()) delay(10)
        return RecordedTake(buildSilence(), TakeStopReason.Requested, null)
    }

    /** Must run before [launch]: the first ViewModel keeps whichever controller it was given. */
    private fun useFakeController(
        loadFile: suspend (Uri) -> LoadedAudioFile = { LoadedAudioFile(FAKE_NAME, buildSilence()) },
        playPreview: suspend (PcmClip) -> Unit = {},
        stopPlayback: () -> Unit = {},
        closePlayback: () -> Unit = {},
        recordTake: suspend (String, () -> Boolean, (Long) -> Unit) -> RecordedTake = { _, _, _ -> error("No recorder") },
        saveRecording: suspend (String, ByteArray) -> Unit = { _, _ -> },
    ) {
        AudioPreviewViewModel.createController = {
            AudioPreviewController(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                ioDispatcher = Dispatchers.IO,
                renderDispatcher = Dispatchers.Default,
                loadFile = loadFile,
                render = { a, _, _ -> a },
                playPreview = playPreview,
                stopPlayback = stopPlayback,
                closePlayback = closePlayback,
                recordTake = { baseName, shouldStop, onProgress, _ -> recordTake(baseName, shouldStop, onProgress) },
                saveRecording = saveRecording,
                // These takes are short and have no count-in.
                loadMetronome = { MetronomeSettings(useForRecording = false) },
            )
        }
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

    private fun selectBoth(name: String) {
        for ((selectTag, nameTag) in listOf(
            AudioPreviewTags.SELECT_A to AudioPreviewTags.NAME_A,
            AudioPreviewTags.SELECT_B to AudioPreviewTags.NAME_B,
        )) {
            compose.onNodeWithTag(selectTag).performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasTestTag(nameTag) and hasText(name), 5_000)
        }
        compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.PLAY) and isEnabled(), 5_000)
    }

    private fun startPlayback() {
        compose.onNodeWithTag(AudioPreviewTags.PLAY).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(AudioPreviewTags.STOP) and isEnabled(), 5_000)
    }

    private fun buildSilence() = PcmClip(ShortArray(480), 48_000)

    private companion object {
        const val FAKE_NAME = "fake.wav"
        const val EXPORTING_STATUS = "합본을 저장하는 중입니다…"
    }
}
