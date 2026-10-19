package com.cassiadev.harmonysyncer

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.cassiadev.harmonysyncer.audio.AudioPreviewController
import com.cassiadev.harmonysyncer.audio.LoadedAudioFile
import com.cassiadev.harmonysyncer.audio.PcmClip
import com.cassiadev.harmonysyncer.audio.wavBytes
import com.cassiadev.harmonysyncer.ui.AudioPreviewTags
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
        wav = File.createTempFile("recreation-", ".wav", context.cacheDir)
        wav.writeBytes(wavBytes(ShortArray(4_800), 48_000))
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
            compose.onNodeWithTag(AudioPreviewTags.OFFSET).performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }

            scenario.recreate()

            compose.onNodeWithTag(AudioPreviewTags.NAME_A).assertTextEquals(wav.name)
            compose.onNodeWithTag(AudioPreviewTags.NAME_B).assertTextEquals(wav.name)
            compose.onNodeWithTag(AudioPreviewTags.VOLUME_B)
                .assert(stateDescription(context.getString(R.string.volume_value, 0)))
            compose.onNodeWithTag(AudioPreviewTags.OFFSET)
                .assert(stateDescription(context.getString(R.string.offset_value, "+100")))
            compose.onNodeWithTag(AudioPreviewTags.PLAY).performScrollTo().assertIsEnabled()
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
            loading.complete(LoadedAudioFile("late.wav", silence()))
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
            compose.onNodeWithTag(AudioPreviewTags.STOP).assertIsNotEnabled()
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

    /** Must run before [launch]: the first ViewModel keeps whichever controller it was given. */
    private fun useFakeController(
        loadFile: suspend (Uri) -> LoadedAudioFile = { LoadedAudioFile(FAKE_NAME, silence()) },
        playPreview: suspend (PcmClip) -> Unit = {},
        stopPlayback: () -> Unit = {},
        closePlayback: () -> Unit = {},
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

    private fun silence() = PcmClip(ShortArray(480), 48_000)

    private fun stateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private companion object {
        const val FAKE_NAME = "fake.wav"
    }
}
