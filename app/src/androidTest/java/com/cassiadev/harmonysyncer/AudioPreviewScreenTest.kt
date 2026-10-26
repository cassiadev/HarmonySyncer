package com.cassiadev.harmonysyncer

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import com.cassiadev.harmonysyncer.audio.AUDIO_TOO_LONG_MESSAGE
import com.cassiadev.harmonysyncer.audio.ClipInfo
import com.cassiadev.harmonysyncer.audio.MixSettings
import com.cassiadev.harmonysyncer.audio.PreviewPhase
import com.cassiadev.harmonysyncer.audio.PreviewUiState
import com.cassiadev.harmonysyncer.audio.RECORDING_INTERRUPTED_NOTICE
import com.cassiadev.harmonysyncer.audio.TrackSlot
import com.cassiadev.harmonysyncer.ui.AudioPreviewScreen
import com.cassiadev.harmonysyncer.ui.AudioPreviewTags
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
    fun startLayoutUntilBothFilesThenReadyLayout() {
        val state = show(buildReadyState().copy(trackB = null))
        compose.onNodeWithText("음성 B 추가").assertExists()
        compose.onNodeWithText("1/2개 선택됨").assertExists()
        compose.onNodeWithTag(AudioPreviewTags.PLAY).assertIsNotEnabled()
            .assertTextEquals("두 파일을 고르면 들을 수 있습니다")
        compose.onNodeWithText("WAV · M4A · 각 60초 이하").performScrollTo().assertExists()

        state.value = buildReadyState()

        compose.onNodeWithText("함께 들을 준비가 됐습니다").assertExists()
        compose.onNodeWithTag(AudioPreviewTags.PLAY).assertIsEnabled().assertTextContains("합쳐 듣기")
        compose.onNodeWithText("준비됐습니다.").assertExists()
    }

    @Test
    fun loadingMarksOnlyThatCardAndExplainsTheLock() {
        show(buildReadyState().copy(phase = PreviewPhase.Loading(TrackSlot.B)))
        compose.onNodeWithText("음성 B 파일을 읽는 중입니다…").assertExists()
        compose.onNode(hasText("읽는 중…") and hasAnyAncestor(hasTestTag(AudioPreviewTags.CARD_B))).assertExists()
        compose.onNode(hasText("읽는 중…") and hasAnyAncestor(hasTestTag(AudioPreviewTags.CARD_A)))
            .assertDoesNotExist()
        compose.onNodeWithTag(AudioPreviewTags.SELECT_A).assertIsNotEnabled()
        compose.onNodeWithTag(AudioPreviewTags.PLAY).assertIsNotEnabled()
        compose.onNodeWithText(LOCK_NOTICE).assertDoesNotExist()
    }

    @Test
    fun renderingAndPlayingShareTheStopButtonAndLockNotice() {
        val state = show(buildReadyState())
        for ((phase, status, button) in listOf(
            Triple(PreviewPhase.Rendering, "두 음성을 합치는 중입니다…", "합치는 중…"),
            Triple(PreviewPhase.Playing, "합쳐 듣는 중입니다.", "정지"),
        )) {
            state.value = buildReadyState().copy(phase = phase)
            compose.onNodeWithText(status).assertExists()
            compose.onNodeWithTag(AudioPreviewTags.STOP).assertIsEnabled().assertTextContains(button)
            compose.onNodeWithTag(AudioPreviewTags.PLAY).assertDoesNotExist()
            compose.onNodeWithText(LOCK_NOTICE).assertExists()
            compose.onNodeWithTag(AudioPreviewTags.VOLUME_B).assertIsNotEnabled()
            compose.onNodeWithTag(AudioPreviewTags.TIMING).assertIsNotEnabled()
            compose.onNodeWithTag(AudioPreviewTags.SELECT_B).assertIsNotEnabled()
        }
    }

    @Test
    fun failedLoadIsShownOnThatCardWithKeptNotice() {
        show(buildReadyState().copy(error = AUDIO_TOO_LONG_MESSAGE, failedSlot = TrackSlot.B))
        compose.onNode(hasText(AUDIO_TOO_LONG_MESSAGE) and hasAnyAncestor(hasTestTag(AudioPreviewTags.CARD_B)))
            .assertExists()
        compose.onAllNodesWithText(AUDIO_TOO_LONG_MESSAGE).assertCountEquals(1)
        compose.onNodeWithText(KEPT_NOTICE).assertExists()
    }

    @Test
    fun firstFailedSelectionHasNoKeptNotice() {
        show(PreviewUiState(error = AUDIO_TOO_LONG_MESSAGE, failedSlot = TrackSlot.A))
        compose.onNode(hasText(AUDIO_TOO_LONG_MESSAGE) and hasAnyAncestor(hasTestTag(AudioPreviewTags.CARD_A)))
            .assertExists()
        compose.onNodeWithText(KEPT_NOTICE).assertDoesNotExist()
    }

    @Test
    fun playbackErrorIsShownOnceAboveTheButton() {
        val message = "이 기기에서 미리 듣기를 재생할 수 없습니다."
        show(buildReadyState().copy(error = message))
        compose.onAllNodesWithText(message).assertCountEquals(1)
        compose.onNode(hasText(message) and hasAnyAncestor(hasTestTag(AudioPreviewTags.CARD_A))).assertDoesNotExist()
        compose.onNodeWithText(KEPT_NOTICE).assertDoesNotExist()
    }

    @Test
    fun volumeAndTimingAreUsedOnNextPlayAndPlaybackLocksEditing() {
        var state by mutableStateOf(buildReadyState())
        var playedSettings: MixSettings? = null
        var stopped = false
        compose.setContent {
            HarmonySyncerTheme {
                AudioPreviewScreen(
                    state = state,
                    onSelectA = {}, onSelectB = {},
                    onSettingsChange = { state = state.copy(settings = it) },
                    onPlay = { playedSettings = state.settings; state = state.copy(phase = PreviewPhase.Playing) },
                    onStop = { stopped = true; state = state.copy(phase = PreviewPhase.Idle) },
                    onLoadSample = {},
                )
            }
        }
        compose.onNodeWithContentDescription("B 음량").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
        compose.onNodeWithContentDescription("B 추가 이동")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.onNodeWithContentDescription("B 추가 이동").assert(hasStateDescription("+100 ms"))
        Espresso.pressBack()

        compose.onNodeWithContentDescription("B 추가 이동").assertDoesNotExist()
        compose.onNodeWithTag(AudioPreviewTags.TIMING).assertTextContains("+100 ms")
        compose.onNodeWithContentDescription("B 음량").assert(hasStateDescription("0%"))
        compose.onNodeWithTag(AudioPreviewTags.PLAY).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MixSettings(volumeB = 0f, offsetMs = 100), playedSettings) }

        compose.onNodeWithTag(AudioPreviewTags.SELECT_A).assertIsNotEnabled()
        compose.onNodeWithTag(AudioPreviewTags.VOLUME_B).assertIsNotEnabled()
        compose.onNodeWithTag(AudioPreviewTags.TIMING).assertIsNotEnabled()
        compose.onNodeWithTag(AudioPreviewTags.STOP).assertIsEnabled().performClick()
        compose.onNodeWithTag(AudioPreviewTags.SELECT_A).assertIsEnabled()
        compose.onNodeWithTag(AudioPreviewTags.PLAY).assertIsEnabled()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test
    fun timingSheetResetsOffset() {
        var changed: MixSettings? = null
        val state = show(buildReadyState().copy(settings = MixSettings(offsetMs = 50)), onSettingsChange = { changed = it })
        compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
        compose.onNodeWithText("0으로").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(MixSettings(offsetMs = 0), changed) }

        state.value = buildReadyState()

        compose.onNodeWithText("0으로").assertIsNotEnabled()
    }

    @Test
    fun appNameIsReadAsHarmonySyncerInKorean() {
        show(PreviewUiState())
        // TTS otherwise guesses "Syncer" as "신서".
        compose.onNodeWithText("HarmonySyncer").assert(hasContentDescription("하모니싱커"))
    }

    @Test
    fun timingRowShowsTogetherForZero() {
        show(buildReadyState())
        compose.onNodeWithTag(AudioPreviewTags.TIMING).assertTextContains("동시")
    }

    @Test
    fun changeButtonsNameTheirSlot() {
        val state = show(buildReadyState())
        compose.onNodeWithContentDescription("음성 A 변경").assertHasClickAction()
        compose.onNodeWithContentDescription("음성 B 변경").assertHasClickAction()

        state.value = PreviewUiState()

        compose.onNodeWithContentDescription("음성 A 파일 선택").assertHasClickAction()
    }

    @Test
    fun longFileNameKeepsChangeButtonVisible() {
        show(buildReadyState().copy(trackA = ClipInfo("가".repeat(80) + ".wav", 48_000, 48_000)))
        compose.onNodeWithTag(AudioPreviewTags.SELECT_A).assertIsDisplayed()
    }

    @Test
    fun differentRateM4aCanPlayWithWav() {
        show(buildReadyState().copy(trackB = ClipInfo("B.m4a", 44_100, 44_100)))
        compose.onNodeWithTag(AudioPreviewTags.SELECT_B).assertIsEnabled()
        compose.onNodeWithTag(AudioPreviewTags.PLAY).assertIsEnabled()
    }

    @Test
    fun sampleLinkOnlyOnStartLayoutAndCreditOnlyForSamples() {
        var sampleRequests = 0
        val state = show(PreviewUiState(), onLoadSample = { sampleRequests++ })
        compose.onNodeWithTag(AudioPreviewTags.SAMPLE).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, sampleRequests) }
        compose.onNodeWithTag(AudioPreviewTags.CREDIT).assertDoesNotExist()

        state.value = buildReadyState().copy(
            trackA = ClipInfo("Locus iste · 소프라노", 48_000, 384_000, isSample = true),
        )

        compose.onNodeWithTag(AudioPreviewTags.SAMPLE).assertDoesNotExist()
        compose.onNodeWithTag(AudioPreviewTags.CREDIT)
            .assertTextEquals("샘플: 브루크너 「Locus iste」 · Dagstuhl ChoirSet · CC BY 4.0")
            .assertHasClickAction()
    }

    @Test
    fun sampleLoadingMarksBothCards() {
        show(PreviewUiState(phase = PreviewPhase.LoadingSample))
        compose.onNodeWithText("샘플을 불러오는 중입니다…").assertExists()
        compose.onAllNodesWithText("읽는 중…").assertCountEquals(2)
        compose.onNodeWithTag(AudioPreviewTags.SAMPLE).assertIsNotEnabled()
    }

    @Test
    fun timingSheetContentScrolls() {
        show(buildReadyState())
        compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
        // Landscape with a large font is taller than the screen; the reset and hint must stay reachable.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("0으로"))).assertExists()
    }

    @Test
    fun timingSheetClosesWhenEditingLocksOrFilesGo() {
        val state = show(buildReadyState())
        compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
        compose.onNodeWithTag(AudioPreviewTags.OFFSET).assertExists()

        state.value = buildReadyState().copy(phase = PreviewPhase.Playing)
        compose.onNodeWithTag(AudioPreviewTags.OFFSET).assertDoesNotExist()
        state.value = buildReadyState()
        compose.onNodeWithTag(AudioPreviewTags.OFFSET).assertDoesNotExist()

        compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
        // Process death restores the flag while the controller starts with no files.
        state.value = PreviewUiState()
        compose.onNodeWithTag(AudioPreviewTags.OFFSET).assertDoesNotExist()
    }

    @Test
    fun creditSurvivesMissingBrowserAndSaysWhatItOpens() {
        var attempts = 0
        compose.setContent {
            val failing = object : UriHandler {
                override fun openUri(uri: String) {
                    attempts++
                    throw IllegalArgumentException("No activity can open $uri")
                }
            }
            CompositionLocalProvider(LocalUriHandler provides failing) {
                HarmonySyncerTheme {
                    AudioPreviewScreen(
                        buildReadyState().copy(trackA = ClipInfo("Locus iste · 소프라노", 48_000, 384_000, isSample = true)),
                        onSelectA = {}, onSelectB = {}, onSettingsChange = {}, onPlay = {}, onStop = {},
                        onLoadSample = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(AudioPreviewTags.CREDIT).assert(hasClickLabel("데이터셋 페이지 열기")).performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithTag(AudioPreviewTags.CREDIT).assertExists()
    }

    @Test
    fun titlesAreHeadingsAndRenderingButtonSaysItStops() {
        val state = show(PreviewUiState())
        compose.onNodeWithText("두 음성을 함께 들어보세요").assert(isHeading())

        state.value = buildReadyState()
        compose.onNodeWithText("함께 들을 준비가 됐습니다").assert(isHeading())
        compose.onNodeWithText("음량").assert(isHeading())
        compose.onNodeWithTag(AudioPreviewTags.TIMING).performScrollTo().performClick()
        compose.onNode(hasText("타이밍 맞추기") and isHeading()).assertExists()
        Espresso.pressBack()

        state.value = buildReadyState().copy(phase = PreviewPhase.Rendering)
        compose.onNodeWithTag(AudioPreviewTags.STOP).assert(hasClickLabel("정지"))
    }

    /** `setContent` may run once per test, so tests change the returned state instead. */

    @Test
    fun recordButtonsAppearWithAMicrophone() {
        val recorded = mutableListOf<TrackSlot>()
        show(buildReadyState().copy(trackB = null), canRecord = true, onRecord = { recorded += it })

        compose.onNodeWithContentDescription("음성 A 녹음").assertIsEnabled()
        compose.onNodeWithContentDescription("음성 B 녹음").performScrollTo().performClick()

        assertEquals(listOf(TrackSlot.B), recorded)
    }

    @Test
    fun recordButtonsHideWithoutAMicrophone() {
        show(buildReadyState(), canRecord = false)

        compose.onNodeWithContentDescription("음성 A 녹음").assertDoesNotExist()
        compose.onNodeWithContentDescription("음성 B 녹음").assertDoesNotExist()
    }

    @Test
    fun recordingCardShowsTimeAndStop() {
        var stops = 0
        show(
            buildReadyState().copy(phase = PreviewPhase.Recording(TrackSlot.A, 12_400)),
            canRecord = true,
            onStopRecording = { stops++ },
        )

        compose.onNodeWithTag(AudioPreviewTags.RECORDING_TIME).assertTextEquals("0:12")
        compose.onNodeWithContentDescription("음성 A 녹음").assertDoesNotExist()
        compose.onNodeWithText("음성 A를 녹음하는 중입니다 · 0:12").assertExists()
        compose.onNodeWithContentDescription("음성 B 녹음").assertIsNotEnabled()
        compose.onNodeWithTag(AudioPreviewTags.SELECT_B).assertIsNotEnabled()
        compose.onNodeWithText(RECORDING_LOCK_NOTICE).performScrollTo().assertExists()
        compose.onNodeWithContentDescription("녹음 정지").performScrollTo().performClick()
        assertEquals(1, stops)
    }

    @Test
    fun savingShowsItsStatus() {
        show(buildReadyState().copy(phase = PreviewPhase.SavingRecording(TrackSlot.B)), canRecord = true)

        compose.onNodeWithText("녹음을 저장하는 중입니다…").assertExists()
        compose.onNode(hasText("저장하는 중…") and hasAnyAncestor(hasTestTag(AudioPreviewTags.CARD_B))).assertExists()
        compose.onNodeWithTag(AudioPreviewTags.STOP_RECORDING).assertDoesNotExist()
    }

    @Test
    fun noticeIsShown() {
        show(buildReadyState().copy(notice = RECORDING_INTERRUPTED_NOTICE))

        compose.onNodeWithText(RECORDING_INTERRUPTED_NOTICE).performScrollTo().assertExists()
    }

    @Test
    fun recordingStatusIsNotAnnouncedEverySecond() {
        // The elapsed time changes every second; announcing it would put TalkBack's voice into the take.
        val state = show(buildReadyState().copy(phase = PreviewPhase.Recording(TrackSlot.A, 12_400)))
        compose.onNodeWithText("음성 A를 녹음하는 중입니다 · 0:12")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))

        state.value = buildReadyState().copy(phase = PreviewPhase.SavingRecording(TrackSlot.A))

        compose.onNodeWithText("녹음을 저장하는 중입니다…").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun noticeIsAnnounced() {
        show(buildReadyState().copy(notice = RECORDING_INTERRUPTED_NOTICE))

        compose.onNode(hasText(RECORDING_INTERRUPTED_NOTICE) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .assertExists()
    }

    private fun show(
        initial: PreviewUiState,
        onSettingsChange: (MixSettings) -> Unit = {},
        onLoadSample: () -> Unit = {},
        canRecord: Boolean = false,
        onRecord: (TrackSlot) -> Unit = {},
        onStopRecording: () -> Unit = {},
    ): MutableState<PreviewUiState> {
        val state = mutableStateOf(initial)
        compose.setContent {
            HarmonySyncerTheme {
                AudioPreviewScreen(
                    state.value,
                    onSelectA = {}, onSelectB = {}, onSettingsChange = onSettingsChange, onPlay = {}, onStop = {},
                    onLoadSample = onLoadSample,
                    canRecord = canRecord,
                    onRecord = onRecord,
                    onStopRecording = onStopRecording,
                )
            }
        }
        return state
    }

    private fun buildReadyState() = PreviewUiState(
        trackA = ClipInfo("A.wav", 48_000, 48_000),
        trackB = ClipInfo("B.wav", 48_000, 48_000),
    )

    private fun hasClickLabel(label: String) = SemanticsMatcher("onClick label is $label") {
        it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == label
    }

    private companion object {
        const val LOCK_NOTICE = "재생 중에는 바꿀 수 없습니다. 정지하면 다시 조절할 수 있습니다."
        const val RECORDING_LOCK_NOTICE = "녹음 중에는 바꿀 수 없습니다. 녹음을 멈추면 다시 조절할 수 있습니다."
        const val KEPT_NOTICE = "기존 파일과 설정은 그대로입니다."
    }
}
