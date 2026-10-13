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
        val harness = Harness(this)
        val a = audio("original-a.wav")
        val b = audio("original-b.wav")
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
        val harness = Harness(this)
        harness.selectPair(audio("a.wav"), audio("b.wav"))
        harness.controller.updateSettings(MixSettings(0.2f, 0.4f, -100))

        harness.controller.selectFile(TrackSlot.B) { audio("phone-recording.m4a", 44_100) }
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
        val harness = Harness(this)
        val loading = CompletableDeferred<LoadedAudioFile>()
        harness.controller.selectFile(TrackSlot.A) { loading.await() }
        assertTrue(harness.controller.state.isLoading)
        assertFalse(harness.controller.state.canEdit)
        harness.controller.selectFile(TrackSlot.B) { error("Must not load while busy") }
        harness.controller.updateSettings(MixSettings(volumeA = 0f))
        harness.controller.play()
        assertEquals(MixSettings(), harness.controller.state.settings)
        runCurrent()
        loading.complete(audio("a.wav"))
        runCurrent()
        assertEquals(null, harness.controller.state.trackB)
        harness.controller.selectFile(TrackSlot.B) { audio("b.wav") }
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
        rendering.complete(audio("preview.wav").clip)
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
        val harness = Harness(this)
        harness.selectPair(audio("a.wav"), audio("b.wav"))
        val rendering = CompletableDeferred<PcmClip>()
        harness.renderBlock = { _, _, _ -> rendering.await() }
        harness.controller.play()
        runCurrent()
        assertEquals(1, harness.renderRequests.size)

        harness.controller.stop()
        assertFalse(harness.controller.state.isPlaying)
        rendering.complete(audio("obsolete.wav").clip)
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
        val harness = Harness(this)
        harness.selectPair(audio("a.wav"), audio("b.wav"))
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
        val harness = Harness(this)
        harness.selectPair(audio("a.wav"), audio("b.wav"))
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
        val harness = Harness(this)
        val loading = CompletableDeferred<LoadedAudioFile>()
        harness.controller.selectFile(TrackSlot.A) { loading.await() }
        runCurrent()
        harness.controller.close()
        loading.complete(audio("late.wav"))
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
        val harness = Harness(this)
        harness.selectPair(audio("a.wav"), audio("b.wav"))
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

    private data class RenderRequest(val a: PcmClip, val b: PcmClip, val settings: MixSettings)

    private class Harness(private val testScope: TestScope) {
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val ownedJob = SupervisorJob()
        val renderRequests = mutableListOf<RenderRequest>()
        val playedClips = mutableListOf<PcmClip>()
        var renderBlock: suspend (PcmClip, PcmClip, MixSettings) -> PcmClip = { a, _, _ -> a }
        var playBlock: suspend (PcmClip) -> Unit = {}
        var stopCalls = 0
        var closeCalls = 0
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
        )

        fun selectPair(a: LoadedAudioFile, b: LoadedAudioFile) {
            controller.selectFile(TrackSlot.A) { a }
            testScope.runCurrent()
            controller.selectFile(TrackSlot.B) { b }
            testScope.runCurrent()
        }
    }

    private fun audio(name: String, sampleRate: Int = 48_000) =
        LoadedAudioFile(name, PcmClip(shortArrayOf(1_000, 2_000, 3_000), sampleRate))
}
