package com.cassiadev.harmonysyncer.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPreviewControllerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `failed replacement keeps cached audio and adjustment settings`() = runTest {
        val harness = FakeAudioHarness(this)
        val a = buildAudio("original-a.wav")
        val b = buildAudio("original-b.wav")
        harness.selectPair(a, b)
        val settings = MixSettings(volumeA = 0.3f, volumeB = 0.7f, offsetMs = 100)
        harness.controller.updateSettings(settings)

        harness.controller.selectFile(TrackSlot.A) {
            throw IllegalArgumentException("지원하지 않는 파일입니다.")
        }
        runCurrent()

        assertEquals(a.name, harness.controller.state.trackA?.name)
        assertEquals(b.name, harness.controller.state.trackB?.name)
        assertEquals(settings, harness.controller.state.settings)
        assertEquals("지원하지 않는 파일입니다.", harness.controller.state.error)
        assertTrue(harness.controller.state.canPlay)
        harness.controller.play()
        runCurrent()
        assertSame(a.clip, harness.renderRequests.single().a)
        assertSame(b.clip, harness.renderRequests.single().b)
        assertEquals(settings, harness.renderRequests.single().settings)
        harness.controller.close()
    }

    @Test
    fun `different rate M4A replacement permits play and resets only offset`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.updateSettings(MixSettings(0.2f, 0.4f, -100))

        harness.controller.selectFile(TrackSlot.B) { buildAudio("phone-recording.m4a", 44_100) }
        runCurrent()

        assertEquals("phone-recording.m4a", harness.controller.state.trackB?.name)
        assertEquals(MixSettings(0.2f, 0.4f, 0), harness.controller.state.settings)
        assertTrue(harness.controller.state.canPlay)
        assertEquals(null, harness.controller.state.error)
        harness.controller.play()
        runCurrent()
        assertEquals(1, harness.renderRequests.size)
        assertEquals(44_100, harness.renderRequests.single().b.sampleRate)
        harness.controller.close()
    }

    @Test
    fun `loading and playback reserve controls immediately and ignore repeated actions`() = runTest {
        val harness = FakeAudioHarness(this)
        val loading = CompletableDeferred<LoadedAudioFile>()
        harness.controller.selectFile(TrackSlot.A) { loading.await() }
        assertTrue(harness.controller.state.isLoading)
        assertFalse(harness.controller.state.canEdit)
        harness.controller.selectFile(TrackSlot.B) { error("Must not load while busy") }
        harness.controller.updateSettings(MixSettings(volumeA = 0f))
        harness.controller.play()
        assertEquals(MixSettings(), harness.controller.state.settings)
        runCurrent()
        loading.complete(buildAudio("a.wav"))
        runCurrent()
        assertEquals(null, harness.controller.state.trackB)
        harness.controller.selectFile(TrackSlot.B) { buildAudio("b.wav") }
        runCurrent()

        val rendering = CompletableDeferred<PcmClip>()
        val playing = CompletableDeferred<Unit>()
        harness.renderBlock = { _, _, _ -> rendering.await() }
        harness.playBlock = { playing.await() }
        harness.controller.play()
        assertTrue(harness.controller.state.isPlaying)
        assertFalse(harness.controller.state.canEdit)
        harness.controller.play()
        harness.controller.updateSettings(MixSettings(volumeB = 0f))
        harness.controller.selectFile(TrackSlot.A) { error("Must not replace during playback") }
        runCurrent()
        assertEquals(1, harness.renderRequests.size)
        assertEquals(MixSettings(), harness.controller.state.settings)
        rendering.complete(buildAudio("preview.wav").clip)
        runCurrent()
        assertTrue(harness.controller.state.isPlaying)
        playing.complete(Unit)
        runCurrent()
        assertFalse(harness.controller.state.isPlaying)
        assertTrue(harness.controller.state.canEdit)
        harness.controller.close()
    }

    @Test
    fun `stop during rendering never sends canceled audio to playback`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val rendering = CompletableDeferred<PcmClip>()
        harness.renderBlock = { _, _, _ -> rendering.await() }
        harness.controller.play()
        runCurrent()
        assertEquals(1, harness.renderRequests.size)

        harness.controller.stop()
        assertFalse(harness.controller.state.isPlaying)
        rendering.complete(buildAudio("obsolete.wav").clip)
        runCurrent()
        assertTrue(harness.playedClips.isEmpty())

        harness.renderBlock = { a, _, _ -> a }
        harness.controller.play()
        runCurrent()
        assertEquals(1, harness.playedClips.size)
        assertFalse(harness.controller.state.isPlaying)
        harness.controller.close()
    }

    @Test
    fun `old playback finally cannot clear the next playback session`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val finishOldCleanup = CompletableDeferred<Unit>()
        val finishNewPlayback = CompletableDeferred<Unit>()
        var oldCleanupStarted = false
        var playCount = 0
        harness.playBlock = {
            if (++playCount == 1) {
                try {
                    awaitCancellation()
                } finally {
                    oldCleanupStarted = true
                    withContext(NonCancellable) { finishOldCleanup.await() }
                }
            } else {
                finishNewPlayback.await()
            }
        }
        harness.controller.play()
        runCurrent()
        harness.controller.stop()
        runCurrent()
        assertTrue(oldCleanupStarted)

        harness.controller.play()
        runCurrent()
        assertEquals(2, playCount)
        assertTrue(harness.controller.state.isPlaying)
        finishOldCleanup.complete(Unit)
        runCurrent()
        assertTrue(harness.controller.state.isPlaying)
        finishNewPlayback.complete(Unit)
        runCurrent()
        assertFalse(harness.controller.state.isPlaying)
        harness.controller.close()
    }

    @Test
    fun `close cancels playback and closes its owned scope exactly once`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        var playbackCleanedUp = false
        harness.playBlock = {
            try {
                awaitCancellation()
            } finally {
                playbackCleanedUp = true
            }
        }
        harness.controller.play()
        runCurrent()
        harness.controller.close()
        harness.controller.close()
        runCurrent()

        assertTrue(playbackCleanedUp)
        assertFalse(harness.controller.state.isPlaying)
        assertFalse(harness.ownedJob.isActive)
        assertEquals(1, harness.stopCalls)
        assertEquals(1, harness.closeCalls)
        harness.controller.play()
        runCurrent()
        assertEquals(1, harness.playedClips.size)
    }

    @Test
    fun `close cancels loading and prevents a late selection from changing state`() = runTest {
        val harness = FakeAudioHarness(this)
        val loading = CompletableDeferred<LoadedAudioFile>()
        harness.controller.selectFile(TrackSlot.A) { loading.await() }
        runCurrent()
        harness.controller.close()
        loading.complete(buildAudio("late.wav"))
        runCurrent()
        assertFalse(harness.controller.state.isLoading)
        assertEquals(null, harness.controller.state.trackA)
        assertFalse(harness.ownedJob.isActive)

        harness.controller.selectFile(TrackSlot.B) { error("Must not load after close") }
        harness.controller.updateSettings(MixSettings(offsetMs = 50))
        runCurrent()
        assertEquals(null, harness.controller.state.trackB)
        assertEquals(MixSettings(), harness.controller.state.settings)
    }

    @Test
    fun `playback failure restores editing and permits another attempt`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.playBlock = { throw IllegalStateException("재생 장치를 준비할 수 없습니다.") }
        harness.controller.play()
        runCurrent()
        assertFalse(harness.controller.state.isPlaying)
        assertTrue(harness.controller.state.canEdit)
        assertNotNull(harness.controller.state.error)

        harness.playBlock = {}
        harness.controller.play()
        runCurrent()
        assertEquals(2, harness.playedClips.size)
        assertEquals(null, harness.controller.state.error)
        harness.controller.close()
    }

    @Test
    fun `running out of memory while mixing explains it and keeps both files`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.renderBlock = { _, _, _ -> throw OutOfMemoryError() }
        harness.controller.play()
        runCurrent()
        assertEquals("메모리가 부족해 두 음성을 합치지 못했습니다. 더 짧은 파일로 다시 시도해 주세요.", harness.controller.state.error)
        assertTrue(harness.controller.state.canEdit)
        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertEquals("b.wav", harness.controller.state.trackB?.name)
        assertEquals(0, harness.playedClips.size)

        harness.renderBlock = { a, _, _ -> a }
        harness.controller.play()
        runCurrent()
        assertEquals(1, harness.playedClips.size)
        harness.controller.close()
    }

    @Test
    fun `repeat previews reuse normalized clips until a file changes`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.normalizeBlock = { a, b -> PcmClip(a.samples, 96_000) to PcmClip(b.samples, 96_000) }
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))

        harness.controller.play()
        runCurrent()
        harness.controller.updateSettings(MixSettings(offsetMs = 120))
        harness.controller.play()
        runCurrent()
        assertEquals(1, harness.normalizeCalls)
        assertEquals(96_000, harness.renderRequests.last().a.sampleRate)
        assertEquals(120, harness.renderRequests.last().settings.offsetMs)

        harness.controller.selectFile(TrackSlot.B) { buildAudio("c.wav") }
        runCurrent()
        harness.controller.play()
        runCurrent()
        assertEquals(2, harness.normalizeCalls)
        harness.controller.close()
    }

    @Test
    fun `close drops the normalized clips`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.play()
        runCurrent()
        assertTrue(harness.controller.hasNormalizedClips)

        harness.controller.close()
        assertFalse(harness.controller.hasNormalizedClips)
    }

    @Test
    fun `normalization stopped before it finishes is not reused`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.normalizeBlock = { _, _ -> awaitCancellation() }
        harness.controller.play()
        runCurrent()
        harness.controller.stop()
        runCurrent()

        harness.normalizeBlock = { a, b -> a to b }
        harness.controller.play()
        runCurrent()
        assertEquals(2, harness.normalizeCalls)
        assertEquals(1, harness.playedClips.size)
        harness.controller.close()
    }

    @Test
    fun `phase tells loading, rendering and playback apart`() = runTest {
        val harness = FakeAudioHarness(this)
        val loading = CompletableDeferred<LoadedAudioFile>()
        harness.controller.selectFile(TrackSlot.B) { loading.await() }
        assertEquals(PreviewPhase.Loading(TrackSlot.B), harness.controller.state.phase)
        loading.complete(buildAudio("b.wav"))
        runCurrent()
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.selectFile(TrackSlot.A) { buildAudio("a.wav") }
        runCurrent()

        val rendering = CompletableDeferred<PcmClip>()
        val playing = CompletableDeferred<Unit>()
        harness.renderBlock = { _, _, _ -> rendering.await() }
        harness.playBlock = { playing.await() }
        harness.controller.play()
        assertEquals(PreviewPhase.Rendering, harness.controller.state.phase)
        rendering.complete(buildAudio("preview.wav").clip)
        runCurrent()
        assertEquals(PreviewPhase.Playing, harness.controller.state.phase)
        playing.complete(Unit)
        runCurrent()
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.close()
    }

    @Test
    fun `stopping while a file loads keeps the load in progress`() = runTest {
        val harness = FakeAudioHarness(this)
        val loading = CompletableDeferred<LoadedAudioFile>()
        harness.controller.selectFile(TrackSlot.A) { loading.await() }
        runCurrent()

        // The activity stops playback on every ON_STOP, including recreation during a load.
        harness.controller.stop()
        assertEquals(PreviewPhase.Loading(TrackSlot.A), harness.controller.state.phase)
        assertFalse(harness.controller.state.canEdit)

        loading.complete(buildAudio("a.wav"))
        runCurrent()
        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.close()
    }

    private data class RenderRequest(val a: PcmClip, val b: PcmClip, val settings: MixSettings)

    @Test
    fun `failed load records its slot until another action replaces the error`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))

        harness.controller.selectFile(TrackSlot.B) { throw IllegalArgumentException("읽을 수 없음") }
        runCurrent()
        assertEquals(TrackSlot.B, harness.controller.state.failedSlot)

        harness.controller.selectFile(TrackSlot.A) { buildAudio("new-a.wav") }
        assertEquals(null, harness.controller.state.failedSlot) // cleared when the next load starts
        runCurrent()

        harness.controller.selectFile(TrackSlot.A) { throw IllegalArgumentException("읽을 수 없음") }
        runCurrent()
        assertEquals(TrackSlot.A, harness.controller.state.failedSlot)
        harness.controller.play()
        assertEquals(null, harness.controller.state.failedSlot)
        harness.controller.close()
    }

    @Test
    fun `settings and playback errors are not tied to a slot`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.selectFile(TrackSlot.B) { throw IllegalArgumentException("읽을 수 없음") }
        runCurrent()
        harness.controller.updateSettings(MixSettings(offsetMs = 999))
        assertNotNull(harness.controller.state.error)
        assertEquals(null, harness.controller.state.failedSlot)

        harness.renderBlock = { _, _, _ -> throw IllegalStateException("재생 실패") }
        harness.controller.play()
        runCurrent()
        assertEquals("재생 실패", harness.controller.state.error)
        assertEquals(null, harness.controller.state.failedSlot)
        harness.controller.close()
    }

    @Test
    fun `sample fills both slots, resets only offset and marks the tracks`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.updateSettings(MixSettings(0.3f, 0.6f, 120))
        val loading = CompletableDeferred<Pair<LoadedAudioFile, LoadedAudioFile>>()
        harness.sampleBlock = { loading.await() }

        harness.controller.loadSample()
        assertEquals(PreviewPhase.LoadingSample, harness.controller.state.phase)
        assertTrue(harness.controller.state.isLoading)
        loading.complete(buildAudio("soprano") to buildAudio("alto"))
        runCurrent()

        with(harness.controller.state) {
            assertEquals("soprano", trackA?.name)
            assertTrue(trackA!!.isSample)
            assertEquals("alto", trackB?.name)
            assertTrue(trackB!!.isSample)
            assertEquals(MixSettings(0.3f, 0.6f, 0), settings)
            assertEquals(PreviewPhase.Idle, phase)
        }
        harness.controller.selectFile(TrackSlot.B) { buildAudio("mine.wav") }
        runCurrent()
        assertFalse(harness.controller.state.trackB!!.isSample)
        assertTrue(harness.controller.state.trackA!!.isSample)
        harness.controller.close()
    }

    @Test
    fun `failed sample load keeps existing clips and is not tied to a slot`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.selectFile(TrackSlot.B) { throw IllegalArgumentException("읽을 수 없음") }
        runCurrent()
        harness.sampleBlock = { throw IllegalStateException("샘플을 읽을 수 없음") }

        harness.controller.loadSample()
        runCurrent()

        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertEquals("b.wav", harness.controller.state.trackB?.name)
        assertEquals("샘플을 읽을 수 없음", harness.controller.state.error)
        assertEquals(null, harness.controller.state.failedSlot)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.close()
    }

    @Test
    fun `sample load ignores repeated and concurrent requests`() = runTest {
        val harness = FakeAudioHarness(this)
        var loads = 0
        val loading = CompletableDeferred<Pair<LoadedAudioFile, LoadedAudioFile>>()
        harness.sampleBlock = { loads++; loading.await() }
        harness.controller.loadSample()
        harness.controller.loadSample()
        harness.controller.selectFile(TrackSlot.A) { error("Must not load while the sample loads") }
        runCurrent()
        assertEquals(1, loads)
        loading.complete(buildAudio("s") to buildAudio("a"))
        runCurrent()

        val fileLoading = CompletableDeferred<LoadedAudioFile>()
        harness.controller.selectFile(TrackSlot.A) { fileLoading.await() }
        harness.controller.loadSample()
        runCurrent()
        assertEquals(1, loads)
        harness.controller.close()
    }

    @Test
    fun `close during sample load prevents a late result`() = runTest {
        val harness = FakeAudioHarness(this)
        val loading = CompletableDeferred<Pair<LoadedAudioFile, LoadedAudioFile>>()
        harness.sampleBlock = { withContext(NonCancellable) { loading.await() } }
        harness.controller.loadSample()
        runCurrent()
        harness.controller.close()
        loading.complete(buildAudio("s") to buildAudio("a"))
        runCurrent()
        assertEquals(null, harness.controller.state.trackA)
        assertEquals(null, harness.controller.state.trackB)
    }


    @Test
    fun `builds the recording name from slot and time`() {
        assertEquals("HarmonySyncer-A-20261026-153012", buildRecordingBaseName(TrackSlot.A, LocalDateTime.of(2026, 10, 26, 15, 30, 12)))
    }

    @Test
    fun `recording fills the slot and resets the offset`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.updateSettings(MixSettings(offsetMs = 100))

        harness.controller.startRecording(TrackSlot.A)
        runCurrent()
        assertEquals(PreviewPhase.Recording(TrackSlot.A, 0), harness.controller.state.phase)
        harness.controller.stopRecording()
        advanceUntilIdle()

        val state = harness.controller.state
        assertEquals("HarmonySyncer-A-20261026-153012.wav", state.trackA?.name)
        assertEquals("b.wav", state.trackB?.name)
        assertEquals(0, state.settings.offsetMs)
        assertEquals(null, state.notice)
        assertEquals(null, state.error)
        assertEquals(PreviewPhase.Idle, state.phase)
        assertFalse(harness.controller.hasNormalizedClips)
        val (savedName, wav) = harness.savedRecordings.single()
        assertEquals("HarmonySyncer-A-20261026-153012.wav", savedName)
        assertTrue(readWavPcm16(wav.inputStream()).samples.contentEquals(shortArrayOf(1, 2, 3)))
    }

    @Test
    fun `progress updates the elapsed time`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.recordBlock = { _, shouldStop, onProgress ->
            onProgress(1_200)
            harness.waitForStop(shouldStop)
        }

        harness.controller.startRecording(TrackSlot.B)
        runCurrent()

        assertEquals(PreviewPhase.Recording(TrackSlot.B, 1_200), harness.controller.state.phase)
        harness.controller.stopRecording()
        advanceUntilIdle()
    }

    @Test
    fun `time limit and interruption leave a notice`() = runTest {
        for ((reason, expected) in listOf(
            TakeStopReason.TimeLimit to RECORDING_TIME_LIMIT_NOTICE,
            TakeStopReason.Interrupted to RECORDING_INTERRUPTED_NOTICE,
        )) {
            val harness = FakeAudioHarness(this)
            harness.recordBlock = { _, _, _ -> RecordedTake(PcmClip(shortArrayOf(1), 48_000), reason, null) }
            harness.controller.startRecording(TrackSlot.A)
            advanceUntilIdle()
            assertEquals(expected, harness.controller.state.notice)
        }

        val harness = FakeAudioHarness(this)
        harness.controller.startRecording(TrackSlot.A)
        runCurrent()
        harness.controller.interruptRecording()
        advanceUntilIdle()
        assertEquals(RECORDING_INTERRUPTED_NOTICE, harness.controller.state.notice)
    }

    @Test
    fun `the next edit clears the notice`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.controller.startRecording(TrackSlot.A)
        runCurrent()
        harness.controller.interruptRecording()
        advanceUntilIdle()

        harness.controller.updateSettings(MixSettings(volumeA = 0.5f))

        assertEquals(null, harness.controller.state.notice)
    }

    @Test
    fun `save failure keeps the take and wins over the notice`() = runTest {
        for ((intact, expected) in listOf(true to RECORDING_SAVE_RETRY_MESSAGE, false to RECORDING_SAVE_LOST_MESSAGE)) {
            val harness = FakeAudioHarness(this)
            val draft = buildDraft("take-$intact", intact)
            harness.recordBlock = { _, _, _ -> RecordedTake(PcmClip(shortArrayOf(1, 2), 48_000), TakeStopReason.TimeLimit, draft) }
            harness.saveBlock = { _, _ -> error("storage full") }

            harness.controller.startRecording(TrackSlot.A)
            advanceUntilIdle()

            val state = harness.controller.state
            assertEquals("take-$intact.wav", state.trackA?.name)
            assertEquals(expected, state.error)
            assertEquals(null, state.notice)
            // Kept either way: a broken draft still holds the part written before it failed.
            assertTrue(File(folder.root, "take-$intact@48000.pcm").exists())
        }
    }

    @Test
    fun `successful save deletes the draft`() = runTest {
        val harness = FakeAudioHarness(this)
        val draft = buildDraft("take", intact = true)
        harness.recordBlock = { _, _, _ -> RecordedTake(PcmClip(shortArrayOf(1, 2), 48_000), TakeStopReason.Requested, draft) }

        harness.controller.startRecording(TrackSlot.B)
        advanceUntilIdle()

        assertEquals("take.wav", harness.controller.state.trackB?.name)
        assertFalse(File(folder.root, "take@48000.pcm").exists())
    }

    @Test
    fun `empty take keeps the slot and skips saving`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val draft = buildDraft("empty", intact = true)
        harness.recordBlock = { _, _, _ -> RecordedTake(PcmClip(ShortArray(0), 48_000), TakeStopReason.Requested, draft) }

        harness.controller.startRecording(TrackSlot.A)
        advanceUntilIdle()

        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertTrue(harness.savedRecordings.isEmpty())
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        assertFalse(File(folder.root, "empty@48000.pcm").exists())
    }

    /** Records [samples] frames into A with a 120 BPM, 4-beat count-in: 96 000 frames at 48 kHz. */
    private suspend fun TestScope.recordCountInTake(samples: Int, reason: TakeStopReason, draftName: String): FakeAudioHarness {
        val harness = FakeAudioHarness(this, metronome = MetronomeSettings(useForRecording = true, clickOn = false))
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val draft = buildDraft(draftName, intact = true)
        harness.anchoredRecordBlock = { _, _, _, _ -> RecordedTake(PcmClip(ShortArray(samples), 48_000), reason, draft) }
        harness.controller.startRecording(TrackSlot.A)
        advanceUntilIdle()
        return harness
    }

    @Test
    fun `stop during count-in discards the take`() = runTest {
        val harness = recordCountInTake(95_999, TakeStopReason.Requested, "short")

        assertTrue(harness.savedRecordings.isEmpty())
        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertFalse(File(folder.root, "short@48000.pcm").exists())
        assertEquals(null, harness.controller.state.notice)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.close()
    }

    @Test
    fun `interruption during count-in also discards`() = runTest {
        val harness = recordCountInTake(95_999, TakeStopReason.Interrupted, "cut")

        assertTrue(harness.savedRecordings.isEmpty())
        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertFalse(File(folder.root, "cut@48000.pcm").exists())
        assertEquals(null, harness.controller.state.notice)
        harness.controller.close()
    }

    @Test
    fun `take as long as the count-in is saved`() = runTest {
        val harness = recordCountInTake(96_000, TakeStopReason.Requested, "long")

        assertEquals(1, harness.savedRecordings.size)
        assertEquals("long.wav", harness.controller.state.trackA?.name)
        harness.controller.close()
    }

    @Test
    fun `recorder failure reports the message`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.recordBlock = { _, _, _ -> throw IllegalStateException(MICROPHONE_UNAVAILABLE_MESSAGE) }

        harness.controller.startRecording(TrackSlot.A)
        advanceUntilIdle()

        assertEquals(MICROPHONE_UNAVAILABLE_MESSAGE, harness.controller.state.error)
        assertEquals(null, harness.controller.state.trackA)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
    }

    @Test
    fun `recorder failure keeps the slot it would have replaced`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.recordBlock = { _, _, _ -> throw IllegalStateException(MICROPHONE_UNAVAILABLE_MESSAGE) }

        harness.controller.startRecording(TrackSlot.A)
        advanceUntilIdle()

        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertEquals(MICROPHONE_UNAVAILABLE_MESSAGE, harness.controller.state.error)
        assertTrue(harness.savedRecordings.isEmpty())
    }

    @Test
    fun `denied microphone reports the message`() = runTest {
        val harness = FakeAudioHarness(this)

        harness.controller.reportMicrophoneDenied()

        assertEquals(MICROPHONE_DENIED_MESSAGE, harness.controller.state.error)
    }

    @Test
    fun `editing is ignored while recording`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.startRecording(TrackSlot.A)
        runCurrent()
        val recording = harness.controller.state

        harness.controller.selectFile(TrackSlot.B) { error("Must not load while recording") }
        harness.controller.loadSample()
        harness.controller.play()
        harness.controller.updateSettings(MixSettings(volumeA = 0.1f))
        harness.controller.startRecording(TrackSlot.B)
        runCurrent()

        assertEquals(recording, harness.controller.state)
        assertTrue(harness.renderRequests.isEmpty())
        harness.controller.stopRecording()
        advanceUntilIdle()
    }

    @Test
    fun `stop requests during saving change nothing`() = runTest {
        val harness = FakeAudioHarness(this)
        val saving = CompletableDeferred<Unit>()
        var saves = 0
        harness.saveBlock = { _, _ ->
            saves++
            saving.await()
        }
        harness.controller.startRecording(TrackSlot.A)
        runCurrent()
        harness.controller.stopRecording()
        advanceTimeBy(20)
        runCurrent()
        assertEquals(PreviewPhase.SavingRecording(TrackSlot.A), harness.controller.state.phase)

        harness.controller.stopRecording()
        harness.controller.interruptRecording()
        saving.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, saves)
        assertEquals(null, harness.controller.state.notice)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
    }

    @Test
    fun `close while recording still saves without filling the slot`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.controller.startRecording(TrackSlot.A)
        runCurrent()

        harness.controller.close()
        advanceUntilIdle()

        assertEquals(1, harness.savedRecordings.size)
        assertEquals(null, harness.controller.state.trackA)
    }

    @Test
    fun `recovery notice reports the count and ignores overlapping requests`() = runTest {
        val harness = FakeAudioHarness(this)
        val recovering = CompletableDeferred<Int>()
        harness.recoverBlock = { recovering.await() }

        harness.controller.recoverDrafts()
        runCurrent()
        harness.controller.recoverDrafts()
        runCurrent()
        recovering.complete(2)
        advanceUntilIdle()

        assertEquals(1, harness.recoverCalls)
        assertEquals(buildRecoveredNotice(2), harness.controller.state.notice)

        val nothing = FakeAudioHarness(this)
        nothing.controller.recoverDrafts()
        advanceUntilIdle()
        assertEquals(null, nothing.controller.state.notice)
    }

    @Test
    fun `a recovery that ends during a take shows its notice after the take`() = runTest {
        val harness = FakeAudioHarness(this)
        val recovering = CompletableDeferred<Int>()
        harness.recoverBlock = { recovering.await() }
        harness.controller.recoverDrafts()
        runCurrent()
        harness.controller.startRecording(TrackSlot.A)
        runCurrent()

        recovering.complete(1)
        runCurrent()

        val noticeDuringTake = harness.controller.state.notice
        // Ends the take even if an assertion fails, so the polling fake recorder cannot hang the test.
        harness.controller.interruptRecording()
        advanceUntilIdle()
        // A notice during a take would be read aloud by TalkBack into the microphone.
        assertEquals(null, noticeDuringTake)
        assertEquals("$RECORDING_INTERRUPTED_NOTICE ${buildRecoveredNotice(1)}", harness.controller.state.notice)
    }

    private fun buildDraft(name: String, intact: Boolean): RecordingDraft {
        val draft = checkNotNull(RecordingDrafts(folder.root).create(name, 48_000))
        if (intact) draft.append(shortArrayOf(1, 2), 0, 2)
        draft.close()
        if (!intact) draft.append(shortArrayOf(1), 0, 1)
        return draft
    }

    /**
     * Builds a controller whose file loading, sample loading, rendering and playback are fakes running on
     * the test scheduler, and records what the controller asks them to render, play, stop and close.
     */
    @Test
    fun `metronome settings load at start and save on change`() = runTest {
        val harness = FakeAudioHarness(this, metronome = MetronomeSettings(bpm = 90))
        assertEquals(90, harness.controller.state.metronome.bpm)

        harness.controller.updateMetronome(MetronomeSettings(bpm = 100))

        assertEquals(100, harness.controller.state.metronome.bpm)
        assertEquals(listOf(MetronomeSettings(bpm = 100)), harness.savedMetronome)
        harness.controller.close()
    }

    @Test
    fun `metronome take freezes settings and reports the count-in start`() = runTest {
        val settings = MetronomeSettings(useForRecording = true, clickOn = false)
        val harness = FakeAudioHarness(this, metronome = settings)
        harness.now = 5_000_000_000
        harness.anchoredRecordBlock = { _, shouldStop, _, anchor ->
            checkNotNull(anchor).stamp.complete(CaptureStamp(48_000, FrameTime(0, 4_900_000_000), measured = true))
            while (!shouldStop()) delay(10)
            RecordedTake(PcmClip(ShortArray(200_000), 48_000), TakeStopReason.Requested, null)
        }

        harness.controller.startRecording(TrackSlot.A)
        assertEquals(PreviewPhase.Recording(TrackSlot.A, 0, settings, null), harness.controller.state.phase)
        runCurrent()
        assertEquals(5_150_000_000L, (harness.controller.state.phase as PreviewPhase.Recording).countInStartNanos)
        harness.controller.updateMetronome(settings.copy(bpm = 90))

        assertEquals(settings, harness.controller.state.metronome)
        assertEquals(settings, (harness.controller.state.phase as PreviewPhase.Recording).metronome)
        harness.controller.stopRecording()
        advanceUntilIdle()
        assertEquals(1, harness.savedRecordings.size)
        harness.controller.close()
    }

    @Test
    fun `take without metronome passes no anchor`() = runTest {
        val harness = FakeAudioHarness(this)

        harness.controller.startRecording(TrackSlot.A)
        runCurrent()
        harness.controller.stopRecording()
        advanceUntilIdle()

        assertEquals(listOf<TakeAnchor?>(null), harness.receivedAnchors)
        harness.controller.close()
    }

    @Test
    fun `stop while preparing saves nothing`() = runTest {
        val harness = FakeAudioHarness(this, metronome = MetronomeSettings(useForRecording = true, clickOn = false))
        harness.controller.selectFile(TrackSlot.A) { buildAudio("a.wav") }
        runCurrent()
        // No capture stamp yet, so the recorder kept nothing.
        harness.anchoredRecordBlock = { _, shouldStop, _, _ ->
            while (!shouldStop()) delay(10)
            RecordedTake(PcmClip(ShortArray(0), 48_000), TakeStopReason.Requested, null)
        }

        harness.controller.startRecording(TrackSlot.A)
        runCurrent()
        harness.controller.stopRecording()
        advanceUntilIdle()

        assertEquals(0, harness.savedRecordings.size)
        assertEquals("a.wav", harness.controller.state.trackA?.name)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.close()
    }

    @Test
    fun `standalone metronome with clicks restarts on change and stops with stop()`() = runTest {
        val harness = FakeAudioHarness(this)

        harness.controller.startMetronome()
        assertEquals(PreviewPhase.Metronome(null), harness.controller.state.phase)
        runCurrent()
        harness.clickStarts.single()(700)
        runCurrent()
        assertEquals(PreviewPhase.Metronome(700), harness.controller.state.phase)
        harness.controller.updateMetronome(MetronomeSettings(bpm = 90))

        assertEquals(listOf(MetronomeSettings(bpm = 90)), harness.restartCalls)
        assertEquals(PreviewPhase.Metronome(null), harness.controller.state.phase)
        assertEquals(1, harness.savedMetronome.size)
        assertFalse(harness.clickRecording.single())
        harness.controller.stop()
        assertTrue(harness.clickStops.single()())
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        advanceUntilIdle()
        harness.controller.close()
    }

    @Test
    fun `standalone metronome without clicks uses the lead`() = runTest {
        val settings = MetronomeSettings(clickOn = false)
        val harness = FakeAudioHarness(this, metronome = settings)
        harness.now = 1_000_000_000

        harness.controller.startMetronome()
        assertEquals(PreviewPhase.Metronome(1_150_000_000), harness.controller.state.phase)
        harness.now = 3_000_000_000
        harness.controller.updateMetronome(settings)

        assertEquals(PreviewPhase.Metronome(3_150_000_000), harness.controller.state.phase)
        runCurrent()
        assertTrue(harness.clickStops.isEmpty())
        harness.controller.close()
    }

    @Test
    fun `turning clicks on while running starts a click track`() = runTest {
        val harness = FakeAudioHarness(this, metronome = MetronomeSettings(clickOn = false))
        harness.controller.startMetronome()

        harness.controller.updateMetronome(MetronomeSettings(clickOn = true))
        runCurrent()

        assertEquals(1, harness.clickStops.size)
        assertEquals(PreviewPhase.Metronome(null), harness.controller.state.phase)
        harness.controller.stop()
        advanceUntilIdle()
        harness.controller.close()
    }

    @Test
    fun `turning clicks off while running stops the click track`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.now = 1_000_000_000
        harness.controller.startMetronome()
        runCurrent()
        harness.clickStarts.single()(700)
        runCurrent()

        harness.controller.updateMetronome(MetronomeSettings(clickOn = false))
        assertTrue(harness.clickStops.single()())
        assertEquals(PreviewPhase.Metronome(1_150_000_000), harness.controller.state.phase)
        // The ended session may still report a count-in; it must not move the beats.
        harness.clickStarts.single()(900)
        runCurrent()

        assertEquals(PreviewPhase.Metronome(1_150_000_000), harness.controller.state.phase)
        advanceUntilIdle()
        harness.controller.close()
    }

    @Test
    fun `standalone metronome keeps showing beats after its click track fails`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.clickFailure = IllegalStateException("이 기기에서 클릭음을 재생할 수 없습니다.")
        harness.now = 1_000_000_000
        harness.controller.startMetronome()
        runCurrent()
        assertEquals(PreviewPhase.Metronome(1_150_000_000), harness.controller.state.phase)

        harness.now = 2_000_000_000
        harness.controller.updateMetronome(MetronomeSettings(bpm = 90))

        assertEquals(PreviewPhase.Metronome(2_150_000_000), harness.controller.state.phase)
        assertTrue(harness.restartCalls.isEmpty())
        harness.controller.stop()
        harness.controller.close()
    }

    @Test
    fun `close stops a running metronome`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.controller.startMetronome()
        runCurrent()

        harness.controller.close()

        assertTrue(harness.clickStops.single()())
    }

    @Test
    fun `metronome blocks other controls`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.startMetronome()

        harness.controller.selectFile(TrackSlot.A) { error("Must not load while the metronome runs") }
        harness.controller.startRecording(TrackSlot.A)
        harness.controller.play()
        harness.controller.updateSettings(MixSettings(volumeA = 0f))
        runCurrent()

        assertEquals(PreviewPhase.Metronome(null), harness.controller.state.phase)
        assertTrue(harness.renderRequests.isEmpty())
        assertTrue(harness.receivedAnchors.isEmpty())
        assertEquals(MixSettings(), harness.controller.state.settings)
        assertEquals("a.wav", harness.controller.state.trackA?.name)
        harness.controller.stop()
        advanceUntilIdle()
        harness.controller.close()
    }

    @Test
    fun `exported mix holds the previewed samples at the higher rate`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.useRealMixing()
        val a = LoadedAudioFile("a.wav", PcmClip(ShortArray(4_410) { (it * 7 % 2_000 - 1_000).toShort() }, 44_100))
        val b = LoadedAudioFile("b.wav", PcmClip(ShortArray(4_800) { (it * 13 % 3_000 - 1_500).toShort() }, 48_000))
        harness.selectPair(a, b)
        val settings = MixSettings(volumeA = 0.8f, volumeB = 0.6f, offsetMs = 12)
        harness.controller.updateSettings(settings)

        harness.controller.exportMix()
        advanceUntilIdle()

        val (name, wav) = harness.savedRecordings.single()
        assertEquals("HarmonySyncer-Mix-20261026-153012.wav", name)
        val saved = readWavPcm16(ByteArrayInputStream(wav))
        assertEquals(48_000, saved.sampleRate)
        assertArrayEquals(preparePreview(a.clip, b.clip, settings).samples, saved.samples)
        val state = harness.controller.state
        assertEquals(MIX_EXPORTED_NOTICE, state.notice)
        assertEquals(PreviewPhase.Idle, state.phase)
        assertEquals("a.wav", state.trackA?.name)
        assertEquals("b.wav", state.trackB?.name)
        assertEquals(settings, state.settings)

        // The same pair and settings sent to the player.
        harness.controller.play()
        advanceUntilIdle()
        assertArrayEquals(harness.playedClips.single().samples, saved.samples)
        harness.controller.close()
    }

    @Test
    fun `a mix longer than sixty seconds is saved whole`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.useRealMixing()
        val frames = 60 * 48_000
        harness.selectPair(
            LoadedAudioFile("a.wav", PcmClip(ShortArray(frames) { 1_000 }, 48_000)),
            LoadedAudioFile("b.wav", PcmClip(ShortArray(frames) { 2_000 }, 48_000)),
        )
        harness.controller.updateSettings(MixSettings(offsetMs = 300))

        harness.controller.exportMix()
        advanceUntilIdle()

        // The WAV reader rejects files over sixty seconds, so the header and tail are read directly.
        val wav = harness.savedRecordings.single().second
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val dataBytes = (frames + 14_400) * Short.SIZE_BYTES
        assertEquals(dataBytes, bytes.getInt(40))
        assertEquals(44 + dataBytes, wav.size)
        // Only B's delayed tail remains at the end, at half gain.
        assertEquals(1_000.toShort(), bytes.getShort(wav.size - Short.SIZE_BYTES))
        harness.controller.close()
    }

    @Test
    fun `only mixes with a sample carry the credit notice`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.sampleBlock = { buildAudio("soprano") to buildAudio("alto") }
        harness.controller.loadSample()
        runCurrent()
        harness.controller.exportMix()
        runCurrent()
        assertEquals("$MIX_EXPORTED_NOTICE $MIX_SAMPLE_CREDIT_NOTICE", harness.controller.state.notice)

        // A is still the sample.
        harness.controller.selectFile(TrackSlot.B) { buildAudio("mine.wav") }
        runCurrent()
        harness.controller.exportMix()
        runCurrent()
        assertEquals("$MIX_EXPORTED_NOTICE $MIX_SAMPLE_CREDIT_NOTICE", harness.controller.state.notice)

        harness.controller.selectFile(TrackSlot.A) { buildAudio("also-mine.wav") }
        runCurrent()
        harness.controller.exportMix()
        runCurrent()
        assertEquals(MIX_EXPORTED_NOTICE, harness.controller.state.notice)
        assertEquals(3, harness.savedRecordings.size)
        harness.controller.close()
    }

    @Test
    fun `a muted sample adds no credit notice`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.sampleBlock = { buildAudio("soprano") to buildAudio("alto") }
        harness.controller.loadSample()
        runCurrent()
        harness.controller.selectFile(TrackSlot.B) { buildAudio("mine.wav") }
        runCurrent()
        // A is the sample at 0%, so none of it reaches the mix.
        harness.controller.updateSettings(MixSettings(volumeA = 0f))

        harness.controller.exportMix()
        runCurrent()

        assertEquals(MIX_EXPORTED_NOTICE, harness.controller.state.notice)
        harness.controller.close()
    }

    @Test
    fun `failed save explains it, unlocks and permits another attempt`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.saveBlock = { _, _ -> throw IOException("녹음 파일을 만들지 못했습니다.") }

        harness.controller.exportMix()
        runCurrent()
        assertEquals(MIX_EXPORT_FAILED_MESSAGE, harness.controller.state.error)
        assertEquals(null, harness.controller.state.notice)
        assertTrue(harness.controller.state.canEdit)

        harness.saveBlock = { name, wav -> harness.savedRecordings += name to wav }
        harness.controller.exportMix()
        runCurrent()
        assertEquals(1, harness.savedRecordings.size)
        assertEquals(null, harness.controller.state.error)
        harness.controller.close()
    }

    @Test
    fun `running out of memory while encoding explains it and saves nothing`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.encodeBlock = { throw OutOfMemoryError() }

        harness.controller.exportMix()
        runCurrent()
        assertEquals(MIX_EXPORT_OUT_OF_MEMORY_MESSAGE, harness.controller.state.error)
        assertTrue(harness.controller.state.canEdit)

        harness.encodeBlock = ::encodeWavPcm16
        harness.renderBlock = { _, _, _ -> throw OutOfMemoryError() }
        harness.controller.exportMix()
        assertEquals(null, harness.controller.state.error)
        runCurrent()
        assertEquals(MIX_EXPORT_OUT_OF_MEMORY_MESSAGE, harness.controller.state.error)
        assertTrue(harness.controller.state.canEdit)
        assertEquals(0, harness.savedRecordings.size)
        harness.controller.close()
    }

    @Test
    fun `export reserves controls immediately and ignores repeated actions`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val saving = harness.holdSaves()

        harness.controller.exportMix()
        assertEquals(PreviewPhase.Exporting, harness.controller.state.phase)
        assertFalse(harness.controller.state.canEdit)
        harness.controller.exportMix()
        harness.controller.play()
        harness.controller.startRecording(TrackSlot.A)
        harness.controller.selectFile(TrackSlot.B) { buildAudio("other.wav") }
        runCurrent()

        assertEquals(PreviewPhase.Exporting, harness.controller.state.phase)
        assertEquals(1, harness.renderRequests.size)
        assertEquals(0, harness.playedClips.size)
        assertEquals("b.wav", harness.controller.state.trackB?.name)
        saving.complete(Unit)
        runCurrent()
        assertEquals(1, harness.savedRecordings.size)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.close()
    }

    @Test
    fun `stop during export keeps saving`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val saving = harness.holdSaves()
        harness.controller.exportMix()
        runCurrent()

        // Going to the background or rotating calls stop().
        harness.controller.stop()
        runCurrent()
        assertEquals(PreviewPhase.Exporting, harness.controller.state.phase)

        saving.complete(Unit)
        runCurrent()
        assertEquals(1, harness.savedRecordings.size)
        assertEquals(MIX_EXPORTED_NOTICE, harness.controller.state.notice)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
        harness.controller.close()
    }

    @Test
    fun `close while mixing cancels the export without an error`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.renderBlock = { _, _, _ -> awaitCancellation() }
        harness.controller.exportMix()
        runCurrent()

        harness.controller.close()
        advanceUntilIdle()

        assertEquals(0, harness.savedRecordings.size)
        assertEquals(null, harness.controller.state.error)
    }

    @Test
    fun `close after the write starts finishes the save and leaves the closed state alone`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val saving = harness.holdSaves()
        harness.controller.exportMix()
        runCurrent()

        harness.controller.close()
        saving.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.savedRecordings.size)
        assertEquals(null, harness.controller.state.notice)
        assertEquals(null, harness.controller.state.error)
        assertEquals(PreviewPhase.Idle, harness.controller.state.phase)
    }

    @Test
    fun `export keeps its normalized clips for the next preview`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        harness.controller.exportMix()
        runCurrent()

        harness.controller.play()
        runCurrent()

        assertEquals(1, harness.normalizeCalls)
        assertEquals(2, harness.renderRequests.size)
        harness.controller.close()
    }

    @Test
    fun `a recovery notice during export stays with the saved notice`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.selectPair(buildAudio("a.wav"), buildAudio("b.wav"))
        val saving = harness.holdSaves()
        harness.controller.exportMix()
        runCurrent()

        harness.recoverBlock = { 1 }
        harness.controller.recoverDrafts()
        runCurrent()
        saving.complete(Unit)
        runCurrent()

        assertEquals("${buildRecoveredNotice(1)} $MIX_EXPORTED_NOTICE", harness.controller.state.notice)
        harness.controller.close()
    }

    @Test
    fun `a recovery that ends after the export keeps the saved notice`() = runTest {
        val harness = FakeAudioHarness(this)
        harness.sampleBlock = { buildAudio("soprano") to buildAudio("alto") }
        harness.controller.loadSample()
        runCurrent()
        val recovering = CompletableDeferred<Int>()
        harness.recoverBlock = { recovering.await() }
        harness.controller.recoverDrafts()
        runCurrent()

        harness.controller.exportMix()
        runCurrent()
        assertEquals("$MIX_EXPORTED_NOTICE $MIX_SAMPLE_CREDIT_NOTICE", harness.controller.state.notice)
        recovering.complete(1)
        runCurrent()

        assertEquals(
            "$MIX_EXPORTED_NOTICE $MIX_SAMPLE_CREDIT_NOTICE ${buildRecoveredNotice(1)}",
            harness.controller.state.notice,
        )
        harness.controller.close()
    }

    // Without a metronome unless a test asks for one: most recording tests predate it.
    private class FakeAudioHarness(
        private val testScope: TestScope,
        metronome: MetronomeSettings = MetronomeSettings(useForRecording = false),
    ) {
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val ownedJob = SupervisorJob()
        val renderRequests = mutableListOf<RenderRequest>()
        val playedClips = mutableListOf<PcmClip>()
        var renderBlock: suspend (PcmClip, PcmClip, MixSettings) -> PcmClip = { a, _, _ -> a }
        var playBlock: suspend (PcmClip) -> Unit = {}
        var stopCalls = 0
        var closeCalls = 0
        var sampleBlock: suspend () -> Pair<LoadedAudioFile, LoadedAudioFile> = { error("Set sampleBlock in the test") }
        var normalizeBlock: suspend (PcmClip, PcmClip) -> Pair<PcmClip, PcmClip> = { a, b -> a to b }
        var normalizeCalls = 0
        var encodeBlock: (PcmClip) -> ByteArray = ::encodeWavPcm16
        var recordBlock: suspend (String, () -> Boolean, (Long) -> Unit) -> RecordedTake = { _, shouldStop, _ ->
            waitForStop(shouldStop)
        }
        val savedRecordings = mutableListOf<Pair<String, ByteArray>>()
        var saveBlock: suspend (String, ByteArray) -> Unit = { name, wav -> savedRecordings += name to wav }
        var recoverBlock: suspend () -> Int = { 0 }
        var recoverCalls = 0
        val receivedAnchors = mutableListOf<TakeAnchor?>()
        var anchoredRecordBlock: suspend (String, () -> Boolean, (Long) -> Unit, TakeAnchor?) -> RecordedTake =
            { baseName, shouldStop, onProgress, _ -> recordBlock(baseName, shouldStop, onProgress) }
        val savedMetronome = mutableListOf<MetronomeSettings>()
        var now = 0L
        val clickStops = mutableListOf<() -> Boolean>()
        val clickStarts = mutableListOf<(Long) -> Unit>()
        val clickRecording = mutableListOf<Boolean>()
        val restartCalls = mutableListOf<MetronomeSettings>()
        /** Thrown by the fake click track as soon as it starts, like a device that cannot play it. */
        var clickFailure: Exception? = null
        val controller = AudioPreviewController(
            scope = CoroutineScope(ownedJob + dispatcher),
            ioDispatcher = dispatcher,
            renderDispatcher = dispatcher,
            loadFile = { error("Use the supplied test loader") },
            render = { a, b, settings ->
                renderRequests += RenderRequest(a, b, settings)
                renderBlock(a, b, settings)
            },
            playPreview = { clip ->
                playedClips += clip
                playBlock(clip)
            },
            stopPlayback = { stopCalls++ },
            closePlayback = { closeCalls++ },
            loadSamplePair = { sampleBlock() },
            normalize = { a, b ->
                normalizeCalls++
                normalizeBlock(a, b)
            },
            encodeWav = { encodeBlock(it) },
            recordTake = { baseName, shouldStop, onProgress, anchor ->
                receivedAnchors += anchor
                anchoredRecordBlock(baseName, shouldStop, onProgress, anchor)
            },
            saveRecording = { name, wav -> saveBlock(name, wav) },
            recoverRecordings = {
                recoverCalls++
                recoverBlock()
            },
            currentTime = { LocalDateTime.of(2026, 10, 26, 15, 30, 12) },
            loadMetronome = { metronome },
            saveMetronome = { savedMetronome += it },
            // Plays until told to stop, like the real blocking click track.
            playClicks = { _, recording, _, shouldStop, onCountInStart ->
                clickFailure?.let { throw it }
                clickRecording += recording
                clickStops += shouldStop
                clickStarts += onCountInStart
                while (!shouldStop()) delay(10)
            },
            restartClicks = { restartCalls += it },
            nanoTime = { now },
        )

        /** Polls like the real recorder until a stop is requested, then returns a three-sample take. */
        suspend fun waitForStop(shouldStop: () -> Boolean): RecordedTake {
            while (!shouldStop()) delay(10)
            return RecordedTake(PcmClip(shortArrayOf(1, 2, 3), 48_000), TakeStopReason.Requested, null)
        }

        /** Makes saves wait until the returned gate completes, as a slow write would. */
        fun holdSaves(): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            saveBlock = { name, wav ->
                gate.await()
                savedRecordings += name to wav
            }
            return gate
        }

        /** Replaces the fake normalizer and renderer with the real ones, for tests that compare samples. */
        fun useRealMixing() {
            normalizeBlock = { a, b -> normalizePreviewRates(a, b) }
            renderBlock = { a, b, settings -> preparePreview(a, b, settings) }
        }

        fun selectPair(a: LoadedAudioFile, b: LoadedAudioFile) {
            controller.selectFile(TrackSlot.A) { a }
            testScope.runCurrent()
            controller.selectFile(TrackSlot.B) { b }
            testScope.runCurrent()
        }
    }

    private fun buildAudio(name: String, sampleRate: Int = 48_000) =
        LoadedAudioFile(name, PcmClip(shortArrayOf(1_000, 2_000, 3_000), sampleRate))
}
