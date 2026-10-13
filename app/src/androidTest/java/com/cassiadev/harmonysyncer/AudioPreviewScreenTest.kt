package com.cassiadev.harmonysyncer

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.cassiadev.harmonysyncer.audio.ClipInfo
import com.cassiadev.harmonysyncer.audio.MixSettings
import com.cassiadev.harmonysyncer.audio.PreviewUiState
import com.cassiadev.harmonysyncer.ui.AudioPreviewScreen
import com.cassiadev.harmonysyncer.ui.theme.HarmonySyncerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AudioPreviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun keepTestScreenAwake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    fun volumeAndOffsetAreUsedOnNextPlayAndPlaybackLocksEditing() {
        var state by mutableStateOf(readyState())
        var playedSettings: MixSettings? = null
        var stopped = false
        compose.setContent {
            HarmonySyncerTheme {
                AudioPreviewScreen(
                    state = state,
                    onSelectA = {}, onSelectB = {},
                    onSettingsChange = { state = state.copy(settings = it) },
                    onPlay = { playedSettings = state.settings; state = state.copy(isPlaying = true) },
                    onStop = { stopped = true; state = state.copy(isPlaying = false) },
                )
            }
        }
        compose.onNodeWithContentDescription("B 음량").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithContentDescription("B 추가 이동").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.onNodeWithContentDescription("B 음량")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0%"))
        compose.onNodeWithContentDescription("B 추가 이동")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "+100 ms"))
        compose.onNodeWithText("합쳐 듣기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MixSettings(volumeB = 0f, offsetMs = 100), playedSettings) }

        compose.onNodeWithText("음성 A 선택").assertIsNotEnabled()
        compose.onNodeWithText("음성 B 선택").assertIsNotEnabled()
        compose.onNodeWithContentDescription("A 음량").assertIsNotEnabled()
        compose.onNodeWithContentDescription("B 음량").assertIsNotEnabled()
        compose.onNodeWithContentDescription("B 추가 이동").assertIsNotEnabled()
        compose.onNodeWithText("합쳐 듣기").assertIsNotEnabled()
        compose.onNodeWithText("정지").assertIsEnabled().performClick()
        compose.onNodeWithText("음성 A 선택").assertIsEnabled()
        compose.onNodeWithText("합쳐 듣기").assertIsEnabled()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test
    fun loadingLocksFileSelectionAndPlayback() {
        show(readyState().copy(isLoading = true))
        compose.onNodeWithText("음성 A 선택").assertIsNotEnabled()
        compose.onNodeWithText("음성 B 선택").assertIsNotEnabled()
        compose.onNodeWithText("합쳐 듣기").assertIsNotEnabled()
        compose.onNodeWithText("정지").assertIsNotEnabled()
    }

    @Test
    fun differentRateM4aCanPlayWithWav() {
        show(readyState().copy(trackB = ClipInfo("B.m4a", 44_100, 44_100)))
        compose.onNodeWithText("음성 B 선택").assertIsEnabled()
        compose.onNodeWithText("합쳐 듣기").assertIsEnabled()
    }

    @Test
    fun bothFilesAreRequiredBeforePlayback() {
        show(readyState().copy(trackB = null))
        compose.onNodeWithText("합쳐 듣기").assertIsNotEnabled()
        compose.onNodeWithText("음성 B 선택").assertIsEnabled()
    }

    private fun show(state: PreviewUiState) {
        compose.setContent {
            HarmonySyncerTheme {
                AudioPreviewScreen(
                    state, onSelectA = {}, onSelectB = {}, onSettingsChange = {}, onPlay = {}, onStop = {},
                )
            }
        }
    }

    private fun readyState() = PreviewUiState(
        trackA = ClipInfo("A.wav", 48_000, 48_000),
        trackB = ClipInfo("B.wav", 48_000, 48_000),
    )
}
