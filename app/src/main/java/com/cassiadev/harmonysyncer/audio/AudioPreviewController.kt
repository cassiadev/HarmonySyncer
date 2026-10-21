package com.cassiadev.harmonysyncer.audio

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TrackSlot { A, B }

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
}

data class PreviewUiState(
    val trackA: ClipInfo? = null,
    val trackB: ClipInfo? = null,
    val phase: PreviewPhase = PreviewPhase.Idle,
    val settings: MixSettings = MixSettings(),
    val error: String? = null,
    /** Set only while [error] reports a failed file load into this slot. */
    val failedSlot: TrackSlot? = null,
) {
    val isLoading: Boolean get() = phase is PreviewPhase.Loading || phase == PreviewPhase.LoadingSample
    val isPlaying: Boolean get() = phase == PreviewPhase.Rendering || phase == PreviewPhase.Playing
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
    )

    var state by mutableStateOf(PreviewUiState())
        private set

    private var clipA: PcmClip? = null
    private var clipB: PcmClip? = null
    private var loadJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackSession = 0L
    private var closed = false

    fun selectFile(slot: TrackSlot, uri: Uri) = selectFile(slot) { loadFile(uri) }

    /** Also permits loaders that do not use Android URIs, including JVM lifecycle tests. */
    internal fun selectFile(slot: TrackSlot, load: suspend () -> LoadedAudioFile) =
        load(PreviewPhase.Loading(slot), failedSlot = slot) { listOf(slot to load()) }

    /** Fills A with the bundled soprano and B with the alto, like choosing both files at once. */
    fun loadSample() = load(PreviewPhase.LoadingSample, failedSlot = null) {
        val (a, b) = loadSamplePair()
        listOf(TrackSlot.A to a, TrackSlot.B to b)
    }

    /**
     * Runs one load at a time. Slots change only after every file loaded; a failure keeps the
     * previous clips and reports [failedSlot] with the error.
     */
    private fun load(
        phase: PreviewPhase,
        failedSlot: TrackSlot?,
        block: suspend () -> List<Pair<TrackSlot, LoadedAudioFile>>,
    ) {
        if (closed || !state.canEdit) return
        state = state.copy(phase = phase, error = null, failedSlot = null)
        loadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val loaded = withContext(ioDispatcher) { block() }
                ensureActive()
                if (closed) return@launch
                for ((slot, file) in loaded) {
                    val info = ClipInfo(
                        file.name,
                        file.clip.sampleRate,
                        file.clip.samples.size,
                        isSample = phase == PreviewPhase.LoadingSample,
                    )
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

    fun updateSettings(settings: MixSettings) {
        if (closed || !state.canEdit) return
        if (!settings.volumeA.isFinite() || !settings.volumeB.isFinite() ||
            settings.volumeA !in 0f..1f || settings.volumeB !in 0f..1f ||
            settings.offsetMs !in -300..300
        ) {
            state = state.copy(error = "음량은 0~100%, B 추가 이동은 −300~+300밀리초로 설정해 주세요.", failedSlot = null)
            return
        }
        state = state.copy(settings = settings, error = null, failedSlot = null)
    }

    fun play() {
        if (closed || !state.canPlay) return
        val a = clipA ?: return
        val b = clipB ?: return
        val settings = state.settings
        val session = ++playbackSession
        // Reserve the session synchronously; repeated taps cannot launch another mix.
        state = state.copy(phase = PreviewPhase.Rendering, error = null, failedSlot = null)
        playbackJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val preview = withContext(renderDispatcher) { render(a, b, settings) }
                ensureActive()
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
        stop()
        closed = true
        loadJob?.cancel()
        loadJob = null
        clipA = null
        clipB = null
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
    )

    private companion object {
        fun createDependencies(context: Context) = Dependencies(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            loader = AudioFileLoader(context.applicationContext.contentResolver),
            player = PcmPreviewPlayer(),
            assets = context.applicationContext.assets,
        )
    }
}
