package com.cassiadev.harmonysyncer.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Maximum supported Android PCM playback rate; also bounds offset padding. */
const val PCM_MAX_SAMPLE_RATE = 192_000

/** Mono PCM16 samples and their sample rate in frames per second. */
class PcmClip(val samples: ShortArray, val sampleRate: Int)

/**
 * Linear volumes and a manual offset of B relative to A, after onset alignment.
 * Positive offsets start B later; negative offsets start B earlier.
 */
data class MixSettings(
    val volumeA: Float = 1f,
    val volumeB: Float = 1f,
    val offsetMs: Int = 0,
)

/**
 * Mixes two mono clips using their volumes and manual relative start position.
 * Each volume is scaled by one half, preserving the existing mixer's headroom.
 * Silence is inserted before the later clip so that both heads and tails survive.
 * Milliseconds are converted to the nearest whole frame, rounding half frames up
 * for either direction. Neither input buffer is changed or shared with the result.
 *
 * Both clips must be nonempty and have the same sample rate in `1..192_000` Hz.
 * Volumes must be finite and in `0f..1f`, and the manual offset must be in
 * `-300..300` milliseconds.
 */
fun renderPreview(a: PcmClip, b: PcmClip, settings: MixSettings): PcmClip {
    validatePreviewInputs(a, b, settings)

    val offsetFrames = (abs(settings.offsetMs).toDouble() * a.sampleRate / 1_000.0)
        .roundToInt()
    val trackA = if (settings.offsetMs < 0) padStart(a.samples, offsetFrames) else a.samples
    val trackB = if (settings.offsetMs > 0) padStart(b.samples, offsetFrames) else b.samples

    return PcmClip(
        samples = mixPcm16(
            trackA = trackA,
            trackB = trackB,
            gainA = settings.volumeA * 0.5f,
            gainB = settings.volumeB * 0.5f,
        ),
        sampleRate = a.sampleRate,
    )
}

/**
 * Applies the existing supplied-onset correction once, then renders the manual mix.
 * Default zero onsets retain each clip's original beginning; no onset detection is
 * performed. Validation rejects mismatched rates before trying to align the clips.
 */
fun preparePreview(
    a: PcmClip,
    b: PcmClip,
    settings: MixSettings,
    onsetA: Int = 0,
    onsetB: Int = 0,
): PcmClip {
    validatePreviewInputs(a, b, settings)
    val aligned = alignFromOnsets(a.samples, onsetA, b.samples, onsetB, a.sampleRate)

    return renderPreview(
        a = PcmClip(aligned.trackA, a.sampleRate),
        b = PcmClip(aligned.trackB, b.sampleRate),
        settings = settings,
    )
}

private fun validatePreviewInputs(a: PcmClip, b: PcmClip, settings: MixSettings) {
    require(a.sampleRate in 1..PCM_MAX_SAMPLE_RATE && b.sampleRate in 1..PCM_MAX_SAMPLE_RATE) {
        "음원의 샘플레이트는 1~192,000Hz여야 합니다."
    }
    require(a.sampleRate == b.sampleRate) { "두 음원의 샘플레이트가 같아야 합니다." }
    require(a.samples.isNotEmpty() && b.samples.isNotEmpty()) { "두 음원에 오디오 데이터가 있어야 합니다." }
    require(settings.volumeA.isFinite() && settings.volumeA in 0f..1f) {
        "A 음량은 0~100%로 설정해 주세요."
    }
    require(settings.volumeB.isFinite() && settings.volumeB in 0f..1f) {
        "B 음량은 0~100%로 설정해 주세요."
    }
    require(settings.offsetMs in -300..300) { "B 추가 이동은 −300~+300밀리초로 설정해 주세요." }
}

private fun padStart(samples: ShortArray, frames: Int): ShortArray {
    require(frames <= Int.MAX_VALUE - samples.size) { "시작 위치를 조절한 음원이 너무 깁니다." }
    return ShortArray(samples.size + frames).also { samples.copyInto(it, destinationOffset = frames) }
}
