package com.cassiadev.harmonysyncer.audio

import android.content.Context
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TrackSlot { A, B }

data class ClipInfo(val name: String, val sampleRate: Int, val frameCount: Int)

data class PreviewUiState(
    val trackA: ClipInfo? = null,
    val trackB: ClipInfo? = null,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val settings: MixSettings = MixSettings(),
    val error: String? = null,
) {
    val canEdit: Boolean get() = !isLoading && !isPlaying
    val canPlay: Boolean
        get() = canEdit && trackA != null && trackB != null &&
            trackA.sampleRate == trackB.sampleRate
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
) {
    constructor(context: Context) : this(createDependencies(context))

    private constructor(dependencies: Dependencies) : this(
        scope = dependencies.scope,
        ioDispatcher = Dispatchers.IO,
        renderDispatcher = Dispatchers.Default,
        loadFile = dependencies.loader::load,
        render = { a, b, settings -> preparePreview(a, b, settings) },
        playPreview = dependencies.player::play,
        stopPlayback = dependencies.player::stop,
        closePlayback = dependencies.player::close,
    )

    var state by mutableStateOf(PreviewUiState())
        private set

    private var clipA: PcmClip? = null
    private var clipB: PcmClip? = null
    private var loadJob: Job? = null
    private var playbackJob: Job? = null
    private var loadSession = 0L
    private var playbackSession = 0L
    private var closed = false

    fun selectFile(slot: TrackSlot, uri: Uri) = selectFile(slot) { loadFile(uri) }

    /** Also permits loaders that do not use Android URIs, including JVM lifecycle tests. */
    internal fun selectFile(slot: TrackSlot, load: suspend () -> LoadedAudioFile) {
        if (closed || !state.canEdit) return
        val session = ++loadSession
        state = state.copy(isLoading = true, error = null)
        loadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val loaded = withContext(ioDispatcher) { load() }
                ensureActive()
                if (closed || session != loadSession) return@launch
                val info = ClipInfo(loaded.name, loaded.clip.sampleRate, loaded.clip.samples.size)
                when (slot) {
                    TrackSlot.A -> {
                        clipA = loaded.clip
                        state = state.copy(trackA = info)
                    }
                    TrackSlot.B -> {
                        clipB = loaded.clip
                        state = state.copy(trackB = info)
                    }
                }
                state = state.copy(
                    settings = state.settings.copy(offsetMs = 0),
                    error = compatibilityError(),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!closed && session == loadSession) {
                    state = state.copy(error = failure.message ?: "파일을 읽을 수 없습니다. 다시 선택해 주세요.")
                }
            } finally {
                if (!closed && session == loadSession) {
                    loadJob = null
                    state = state.copy(isLoading = false)
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
            state = state.copy(error = "음량은 0~100%, B 추가 이동은 −300~+300밀리초로 설정해 주세요.")
            return
        }
        state = state.copy(settings = settings, error = compatibilityError())
    }

    fun play() {
        if (closed || !state.canPlay) return
        val a = clipA ?: return
        val b = clipB ?: return
        val settings = state.settings
        // Check before alignment so differently sampled inputs never share a frame grid.
        compatibilityError()?.let { error ->
            state = state.copy(error = error)
            return
        }
        val session = ++playbackSession
        // Reserve the session synchronously; repeated taps cannot launch another mix.
        state = state.copy(isPlaying = true, error = null)
        playbackJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val preview = withContext(renderDispatcher) { render(a, b, settings) }
                ensureActive()
                playPreview(preview)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!closed && session == playbackSession) {
                    state = state.copy(error = failure.message ?: "미리 듣기를 재생할 수 없습니다. 다시 시도해 주세요.")
                }
            } finally {
                // A stopped render/playback may finish after the next session has begun.
                if (!closed && session == playbackSession) {
                    playbackJob = null
                    state = state.copy(isPlaying = false)
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
        state = state.copy(isPlaying = false)
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        loadSession++
        loadJob?.cancel()
        loadJob = null
        clipA = null
        clipB = null
        state = state.copy(isLoading = false)
        try {
            closePlayback()
        } finally {
            scope.cancel()
        }
    }

    private fun compatibilityError(): String? {
        val a = clipA ?: return null
        val b = clipB ?: return null
        return if (a.sampleRate != b.sampleRate) {
            "두 파일의 샘플레이트가 다릅니다 (A: ${a.sampleRate}Hz, B: ${b.sampleRate}Hz). 같은 샘플레이트의 파일을 선택해 주세요."
        } else {
            null
        }
    }

    private data class Dependencies(
        val scope: CoroutineScope,
        val loader: AudioFileLoader,
        val player: PcmPreviewPlayer,
    )

    private companion object {
        fun createDependencies(context: Context) = Dependencies(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            loader = AudioFileLoader(context.applicationContext.contentResolver),
            player = PcmPreviewPlayer(),
        )
    }
}
