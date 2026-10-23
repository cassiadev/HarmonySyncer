package com.cassiadev.harmonysyncer.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPreviewControllerTest {
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

    /**
     * Builds a controller whose file loading, sample loading, rendering and playback are fakes running on
     * the test scheduler, and records what the controller asks them to render, play, stop and close.
     */
    private class FakeAudioHarness(private val testScope: TestScope) {
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
        )

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
