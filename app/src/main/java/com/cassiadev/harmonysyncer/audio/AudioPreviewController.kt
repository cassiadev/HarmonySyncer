package com.cassiadev.harmonysyncer.audio

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TrackSlot { A, B }

private val RECORDING_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/** `HarmonySyncer-A-20261026-153012`; the draft or the saved file adds its extension. */
internal fun buildRecordingBaseName(slot: TrackSlot, time: LocalDateTime) =
    "HarmonySyncer-${slot.name}-${time.format(RECORDING_TIME_FORMAT)}"

/** [isSample] marks the bundled harmony sample, whose recordings need a CC BY credit on screen. */
data class ClipInfo(val name: String, val sampleRate: Int, val frameCount: Int, val isSample: Boolean = false) {
    val durationSeconds: Float get() = frameCount / sampleRate.toFloat()
}

/** What the preview is busy with; only one load or one preview runs at a time. */
sealed interface PreviewPhase {
    data object Idle : PreviewPhase
    data class Loading(val slot: TrackSlot) : PreviewPhase
    /** Loading the bundled harmony sample into both slots. */
    data object LoadingSample : PreviewPhase
    data object Rendering : PreviewPhase
    data object Playing : PreviewPhase
    /** Recording into [slot]; [elapsedMs] counts the audio captured so far. */
    data class Recording(val slot: TrackSlot, val elapsedMs: Long) : PreviewPhase
    /** Saving the finished take of [slot] to shared storage. */
    data class SavingRecording(val slot: TrackSlot) : PreviewPhase
}

data class PreviewUiState(
    val trackA: ClipInfo? = null,
    val trackB: ClipInfo? = null,
    val phase: PreviewPhase = PreviewPhase.Idle,
    val settings: MixSettings = MixSettings(),
    val error: String? = null,
    /** Set only while [error] reports a failed file load into this slot. */
    val failedSlot: TrackSlot? = null,
    /** Information that is not an error, such as a take saved after an interruption. */
    val notice: String? = null,
) {
    val isLoading: Boolean get() = phase is PreviewPhase.Loading || phase == PreviewPhase.LoadingSample
    val isPlaying: Boolean get() = phase == PreviewPhase.Rendering || phase == PreviewPhase.Playing
    val isRecording: Boolean get() = phase is PreviewPhase.Recording || phase is PreviewPhase.SavingRecording
    val canEdit: Boolean get() = phase == PreviewPhase.Idle
    val canPlay: Boolean
        get() = canEdit && trackA != null && trackB != null
}

/** Main-thread UI controller. Sample arrays stay private and are reused across previews. */
class AudioPreviewController internal constructor(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val renderDispatcher: CoroutineDispatcher,
    private val loadFile: suspend (Uri) -> LoadedAudioFile,
    private val render: suspend (PcmClip, PcmClip, MixSettings) -> PcmClip,
    private val playPreview: suspend (PcmClip) -> Unit,
    private val stopPlayback: () -> Unit,
    private val closePlayback: () -> Unit,
    private val loadSamplePair: suspend () -> Pair<LoadedAudioFile, LoadedAudioFile> = {
        error("No bundled sample in this controller")
    },
    // The identity is always correct: render still resamples clips that were not normalized.
    private val normalize: suspend (PcmClip, PcmClip) -> Pair<PcmClip, PcmClip> = { a, b -> a to b },
    private val recordTake: suspend (baseName: String, shouldStop: () -> Boolean, onProgress: (Long) -> Unit) -> RecordedTake =
        { _, _, _ -> error("No recorder in this controller") },
    private val saveRecording: suspend (displayName: String, wav: ByteArray) -> Unit =
        { _, _ -> error("No recording store in this controller") },
    private val recoverRecordings: suspend () -> Int = { 0 },
    private val currentTime: () -> LocalDateTime = LocalDateTime::now,
) {
    constructor(context: Context) : this(createDependencies(context))

    private constructor(dependencies: Dependencies) : this(
        scope = dependencies.scope,
        ioDispatcher = Dispatchers.IO,
        renderDispatcher = Dispatchers.Default,
        loadFile = dependencies.loader::load,
        render = { a, b, settings ->
            val context = currentCoroutineContext()
            preparePreview(a, b, settings, checkActive = { context.ensureActive() })
        },
        playPreview = dependencies.player::play,
        stopPlayback = dependencies.player::stop,
        closePlayback = dependencies.player::close,
        loadSamplePair = { BundledVocalDemo.loadSamplePair(dependencies.assets::open) },
        normalize = { a, b ->
            val context = currentCoroutineContext()
            normalizePreviewRates(a, b, checkActive = { context.ensureActive() })
        },
        recordTake = { baseName, shouldStop, onProgress ->
            dependencies.recorder.record({ rate -> dependencies.drafts.create(baseName, rate) }, shouldStop, onProgress)
        },
        saveRecording = { name, wav -> dependencies.store.save(name, wav) },
        recoverRecordings = { saveLeftoverDrafts(dependencies.drafts) { name, wav -> dependencies.store.save(name, wav) } },
    )

    var state by mutableStateOf(PreviewUiState())
        private set

    private var clipA: PcmClip? = null
    private var clipB: PcmClip? = null
    /** clipA and clipB at a shared rate, kept across previews until either file changes. */
    private var normalizedClips: Pair<PcmClip, PcmClip>? = null

    // Nothing observable remains after close, so tests read the cache directly.
    @VisibleForTesting
    internal val hasNormalizedClips: Boolean get() = normalizedClips != null
    private var loadJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackSession = 0L
    private var recordingJob: Job? = null
    private var recoveryJob: Job? = null
    /** Takes a recovery saved while a take was recording; announced only once the microphone is off. */
    private var recoveredDuringTake = 0
    // The recorder polls this from its IO thread; only the first request of a take counts.
    @Volatile private var stopRequest: StopRequest? = null
    private var closed = false

    private enum class StopRequest { User, Interruption }

    private class RecordingOutcome(
        val file: LoadedAudioFile,
        val stopReason: TakeStopReason,
        val saved: Boolean,
        val draftIntact: Boolean,
    )

    fun selectFile(slot: TrackSlot, uri: Uri) = selectFile(slot) { loadFile(uri) }

    /** Also permits loaders that do not use Android URIs, including JVM lifecycle tests. */
    internal fun selectFile(slot: TrackSlot, load: suspend () -> LoadedAudioFile) =
        startLoad(PreviewPhase.Loading(slot), failedSlot = slot) { listOf(slot to load()) }

    /** Fills A with the bundled soprano and B with the alto, like choosing both files at once. */
    fun loadSample() = startLoad(PreviewPhase.LoadingSample, failedSlot = null) {
        val (a, b) = loadSamplePair()
        listOf(TrackSlot.A to a, TrackSlot.B to b)
    }

    /**
     * Runs one load at a time. Slots change only after every file loaded; a failure keeps the
     * previous clips and reports [failedSlot] with the error.
     */
    private fun startLoad(
        phase: PreviewPhase,
        failedSlot: TrackSlot?,
        block: suspend () -> List<Pair<TrackSlot, LoadedAudioFile>>,
    ) {
        if (closed || !state.canEdit) return
        state = state.copy(phase = phase, error = null, failedSlot = null, notice = null)
        loadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val loaded = withContext(ioDispatcher) { block() }
                ensureActive()
                if (closed) return@launch
                applyLoaded(loaded, isSample = phase == PreviewPhase.LoadingSample)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!closed) {
                    state = state.copy(
                        error = failure.message ?: "파일을 읽을 수 없습니다. 다시 선택해 주세요.",
                        failedSlot = failedSlot,
                    )
                }
            } finally {
                if (!closed) {
                    loadJob = null
                    state = state.copy(phase = PreviewPhase.Idle)
                }
            }
        }
        loadJob?.start()
    }

    /** Replaces the given slots as a file selection does: the shared-rate cache and B's offset reset. */
    private fun applyLoaded(loaded: List<Pair<TrackSlot, LoadedAudioFile>>, isSample: Boolean) {
        normalizedClips = null
        for ((slot, file) in loaded) {
            val info = ClipInfo(file.name, file.clip.sampleRate, file.clip.samples.size, isSample = isSample)
            when (slot) {
                TrackSlot.A -> {
                    clipA = file.clip
                    state = state.copy(trackA = info)
                }
                TrackSlot.B -> {
                    clipB = file.clip
                    state = state.copy(trackB = info)
                }
            }
        }
        state = state.copy(
            settings = state.settings.copy(offsetMs = 0),
            error = null,
            failedSlot = null,
        )
    }

    /**
     * Records into [slot] without accompaniment, saves the take to shared storage and then puts it in
     * the slot. Recording and saving run under [NonCancellable] so [close] (leaving the app) still saves.
     */
    fun startRecording(slot: TrackSlot) {
        if (closed || !state.canEdit) return
        val baseName = buildRecordingBaseName(slot, currentTime())
        stopRequest = null
        state = state.copy(phase = PreviewPhase.Recording(slot, 0), error = null, failedSlot = null, notice = null)
        recordingJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val outcome = withContext(ioDispatcher + NonCancellable) { recordAndSave(slot, baseName) }
                if (closed || outcome == null) return@launch
                applyLoaded(listOf(slot to outcome.file), isSample = false)
                state = if (!outcome.saved) {
                    state.copy(error = if (outcome.draftIntact) RECORDING_SAVE_RETRY_MESSAGE else RECORDING_SAVE_LOST_MESSAGE)
                } else {
                    state.copy(
                        notice = when {
                            outcome.stopReason == TakeStopReason.TimeLimit -> RECORDING_TIME_LIMIT_NOTICE
                            outcome.stopReason == TakeStopReason.Interrupted ||
                                stopRequest == StopRequest.Interruption -> RECORDING_INTERRUPTED_NOTICE
                            else -> null
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!closed) state = state.copy(error = failure.message ?: MICROPHONE_UNAVAILABLE_MESSAGE, failedSlot = null)
            } finally {
                if (!closed) {
                    recordingJob = null
                    state = state.copy(phase = PreviewPhase.Idle)
                    if (recoveredDuringTake > 0) {
                        val recovered = buildRecoveredNotice(recoveredDuringTake)
                        recoveredDuringTake = 0
                        state = state.copy(notice = listOfNotNull(state.notice, recovered).joinToString(" "))
                    }
                }
            }
        }
        recordingJob?.start()
    }

    /** Runs on IO; null for a take without samples. Phase changes are posted back to [scope]. */
    private suspend fun recordAndSave(slot: TrackSlot, baseName: String): RecordingOutcome? {
        val take = recordTake(baseName, { stopRequest != null }) { elapsedMs ->
            scope.launch {
                val phase = state.phase
                if (phase is PreviewPhase.Recording && phase.slot == slot) {
                    state = state.copy(phase = PreviewPhase.Recording(slot, elapsedMs))
                }
            }
        }
        val draft = take.draft
        if (take.clip.samples.isEmpty()) {
            draft?.delete()
            draft?.release()
            return null
        }
        scope.launch {
            if (state.phase is PreviewPhase.Recording) state = state.copy(phase = PreviewPhase.SavingRecording(slot))
        }
        val name = draft?.displayName ?: "$baseName.wav"
        val saved = try {
            saveRecording(name, encodeWavPcm16(take.clip))
            true
        } catch (_: Exception) {
            false
        }
        // A failed save keeps the draft for the next recovery.
        if (saved) draft?.delete()
        draft?.release()
        return RecordingOutcome(LoadedAudioFile(name, take.clip), take.stopReason, saved, draft?.isIntact == true)
    }

    fun stopRecording() = requestRecordingStop(StopRequest.User)

    /** Leaving the app stops the take like the stop button, but the saved take gets a notice. */
    fun interruptRecording() = requestRecordingStop(StopRequest.Interruption)

    private fun requestRecordingStop(request: StopRequest) {
        if (state.phase is PreviewPhase.Recording && stopRequest == null) stopRequest = request
    }

    fun reportMicrophoneDenied() {
        if (closed) return
        state = state.copy(error = MICROPHONE_DENIED_MESSAGE, failedSlot = null, notice = null)
    }

    /** Saves drafts left by an earlier process or a failed save; one recovery runs at a time. */
    fun recoverDrafts() {
        if (closed || recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            val count = try {
                withContext(ioDispatcher) { recoverRecordings() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                0
            }
            if (count <= 0 || closed) return@launch
            // TalkBack reads a new notice aloud, which would go into the take through the microphone.
            if (state.isRecording) {
                recoveredDuringTake += count
            } else {
                state = state.copy(notice = buildRecoveredNotice(count))
            }
        }
    }

    fun updateSettings(settings: MixSettings) {
        if (closed || !state.canEdit) return
        if (!settings.volumeA.isFinite() || !settings.volumeB.isFinite() ||
            settings.volumeA !in 0f..1f || settings.volumeB !in 0f..1f ||
            settings.offsetMs !in -300..300
        ) {
            state = state.copy(error = "음량은 0~100%, B 추가 이동은 −300~+300밀리초로 설정해 주세요.", failedSlot = null)
            return
        }
        state = state.copy(settings = settings, error = null, failedSlot = null, notice = null)
    }

    fun play() {
        if (closed || !state.canPlay) return
        val a = clipA ?: return
        val b = clipB ?: return
        val settings = state.settings
        val cached = normalizedClips
        val session = ++playbackSession
        // Reserve the session synchronously; repeated taps cannot launch another mix.
        state = state.copy(phase = PreviewPhase.Rendering, error = null, failedSlot = null, notice = null)
        playbackJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val (normalized, preview) = withContext(renderDispatcher) {
                    try {
                        val clips = cached ?: normalize(a, b)
                        clips to render(clips.first, clips.second, settings)
                    } catch (outOfMemory: OutOfMemoryError) {
                        // Only rate conversion and mixing are caught: their partial buffers are unreachable now,
                        // so editing can resume.
                        throw IllegalStateException(
                            "메모리가 부족해 두 음성을 합치지 못했습니다. 더 짧은 파일로 다시 시도해 주세요.",
                            outOfMemory,
                        )
                    }
                }
                ensureActive()
                // A stopped session or replaced file must not leave clips from another pair behind.
                if (session == playbackSession && clipA === a && clipB === b) normalizedClips = normalized
                state = state.copy(phase = PreviewPhase.Playing)
                playPreview(preview)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!closed && session == playbackSession) {
                    state = state.copy(
                        error = failure.message ?: "미리 듣기를 재생할 수 없습니다. 다시 시도해 주세요.",
                        failedSlot = null,
                    )
                }
            } finally {
                // A stopped render/playback may finish after the next session has begun.
                if (!closed && session == playbackSession) {
                    playbackJob = null
                    state = state.copy(phase = PreviewPhase.Idle)
                }
            }
        }
        playbackJob?.start()
    }

    fun stop() {
        playbackSession++
        playbackJob?.cancel()
        playbackJob = null
        stopPlayback()
        // ON_STOP also arrives while a file loads; that load must keep running.
        if (state.isPlaying) state = state.copy(phase = PreviewPhase.Idle)
    }

    fun close() {
        if (closed) return
        // An unfinished take stops and still saves; see startRecording.
        if (stopRequest == null) stopRequest = StopRequest.Interruption
        stop()
        closed = true
        loadJob?.cancel()
        loadJob = null
        clipA = null
        clipB = null
        normalizedClips = null
        state = state.copy(phase = PreviewPhase.Idle)
        try {
            closePlayback()
        } finally {
            scope.cancel()
        }
    }

    private data class Dependencies(
        val scope: CoroutineScope,
        val loader: AudioFileLoader,
        val player: PcmPreviewPlayer,
        val assets: AssetManager,
        val recorder: TakeRecorder,
        val store: RecordingStore,
        val drafts: RecordingDrafts,
    )

    private companion object {
        fun createDependencies(context: Context) = Dependencies(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            loader = AudioFileLoader(context.applicationContext.contentResolver),
            player = PcmPreviewPlayer(),
            assets = context.applicationContext.assets,
            recorder = TakeRecorder(context.applicationContext),
            store = RecordingStore(context.applicationContext.contentResolver),
            drafts = RecordingDrafts(File(context.applicationContext.filesDir, "recordings-in-progress")),
        )
    }
}
