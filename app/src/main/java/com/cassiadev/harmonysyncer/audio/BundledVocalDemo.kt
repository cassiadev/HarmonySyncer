package com.cassiadev.harmonysyncer.audio

import androidx.annotation.WorkerThread
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed soprano/alto excerpt, prepared offline as signed, little-endian mono PCM16.
 * Both files start at the same musical reference (measure 1 of the same performance),
 * not at each singer's first audible sample. See assets/vocals/README.md for provenance.
 */
object BundledVocalDemo {
    const val SAMPLE_RATE = 48_000
    const val FRAME_COUNT = SAMPLE_RATE * 8
    const val TRACK_A_NAME = "Locus iste · 소프라노"
    const val TRACK_B_NAME = "Locus iste · 알토"

    private const val TRACK_A_ASSET = "vocals/locus_iste_soprano.pcm"
    private const val TRACK_B_ASSET = "vocals/locus_iste_alto.pcm"
    private const val REFERENCE_A_FRAME = 0
    private const val REFERENCE_B_FRAME = 0

    /**
     * Loads the two bundled recordings and applies the existing alignment operation.
     * [openAsset] can be supplied as `assets::open` on Android. Each stream is closed here.
     * File I/O and sample processing must run on a worker thread.
     */
    @WorkerThread
    fun loadAligned(openAsset: (String) -> InputStream): AlignedTracks = alignFromOnsets(
        trackA = readTrack(TRACK_A_ASSET, openAsset),
        onsetA = REFERENCE_A_FRAME,
        trackB = readTrack(TRACK_B_ASSET, openAsset),
        onsetB = REFERENCE_B_FRAME,
        sampleRate = SAMPLE_RATE,
    )

    /**
     * Loads the aligned pair as the A/B clips the preview controller plays, so the user can
     * change volumes and timing like for their own files.
     */
    @WorkerThread
    fun loadSamplePair(openAsset: (String) -> InputStream): Pair<LoadedAudioFile, LoadedAudioFile> {
        val aligned = loadAligned(openAsset)
        return LoadedAudioFile(TRACK_A_NAME, PcmClip(aligned.trackA, SAMPLE_RATE)) to
            LoadedAudioFile(TRACK_B_NAME, PcmClip(aligned.trackB, SAMPLE_RATE))
    }

    private fun readTrack(path: String, openAsset: (String) -> InputStream): ShortArray {
        val bytes = openAsset(path).use { it.readBytes() }
        check(bytes.size == FRAME_COUNT * Short.SIZE_BYTES) {
            "$path must contain exactly $FRAME_COUNT mono PCM16 frames"
        }
        return ShortArray(FRAME_COUNT).also { samples ->
            ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples)
        }
    }
}
