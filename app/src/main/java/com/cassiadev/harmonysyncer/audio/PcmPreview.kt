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
 * Both clips must be nonempty with sample rates in `1..192_000` Hz. Different
 * rates are resampled to the higher rate before offsets and mixing are applied.
 * Volumes must be finite and in `0f..1f`, and the manual offset must be in
 * `-300..300` milliseconds.
 */
fun renderPreview(
    a: PcmClip,
    b: PcmClip,
    settings: MixSettings,
    checkActive: () -> Unit = {},
): PcmClip {
    validatePreviewInputs(a, b, settings)
    val rate = maxOf(a.sampleRate, b.sampleRate)
    val normalizedA = resamplePcmUp(a, rate, checkActive)
    val normalizedB = resamplePcmUp(b, rate, checkActive)
    return PcmClip(
        mixWithOffset(normalizedA.samples, normalizedB.samples, rate, settings, alignedLength = null, checkActive),
        rate,
    )
}

/**
 * Applies the existing supplied-onset correction once, then renders the manual mix.
 * Default zero onsets retain each clip's original beginning; no onset detection is
 * performed. Onsets are indices in each original clip, before resampling.
 */
fun preparePreview(
    a: PcmClip,
    b: PcmClip,
    settings: MixSettings,
    onsetA: Int = 0,
    onsetB: Int = 0,
    checkActive: () -> Unit = {},
): PcmClip {
    validatePreviewInputs(a, b, settings)
    require(onsetA in a.samples.indices) { "onsetA must be a valid index in trackA" }
    require(onsetB in b.samples.indices) { "onsetB must be a valid index in trackB" }
    val rate = maxOf(a.sampleRate, b.sampleRate)
    val trimmedA = if (onsetA == 0) a else PcmClip(a.samples.copyOfRange(onsetA, a.samples.size), a.sampleRate)
    val trimmedB = if (onsetB == 0) b else PcmClip(b.samples.copyOfRange(onsetB, b.samples.size), b.sampleRate)
    val normalizedA = resamplePcmUp(trimmedA, rate, checkActive)
    val normalizedB = resamplePcmUp(trimmedB, rate, checkActive)
    // Onset alignment treats both tracks as the longer length, so the shorter tail is silence.
    val alignedLength = maxOf(normalizedA.samples.size, normalizedB.samples.size)
    return PcmClip(
        mixWithOffset(normalizedA.samples, normalizedB.samples, rate, settings, alignedLength, checkActive),
        rate,
    )
}

/**
 * Resamples both clips to the preview rate after the renderers' clip checks. Rendering the
 * result equals rendering the inputs, so a caller may keep it between previews of the same pair.
 */
internal fun normalizePreviewRates(
    a: PcmClip,
    b: PcmClip,
    checkActive: () -> Unit = {},
): Pair<PcmClip, PcmClip> {
    validatePreviewClips(a, b)
    val rate = maxOf(a.sampleRate, b.sampleRate)
    return resamplePcmUp(a, rate, checkActive) to resamplePcmUp(b, rate, checkActive)
}

private fun validatePreviewClips(a: PcmClip, b: PcmClip) {
    require(a.sampleRate in 1..PCM_MAX_SAMPLE_RATE && b.sampleRate in 1..PCM_MAX_SAMPLE_RATE) {
        "음원의 샘플레이트는 1~192,000Hz여야 합니다."
    }
    require(a.samples.isNotEmpty() && b.samples.isNotEmpty()) { "두 음원에 오디오 데이터가 있어야 합니다." }
}

private fun validatePreviewInputs(a: PcmClip, b: PcmClip, settings: MixSettings) {
    validatePreviewClips(a, b)
    require(settings.volumeA.isFinite() && settings.volumeA in 0f..1f) {
        "A 음량은 0~100%로 설정해 주세요."
    }
    require(settings.volumeB.isFinite() && settings.volumeB in 0f..1f) {
        "B 음량은 0~100%로 설정해 주세요."
    }
    require(settings.offsetMs in -300..300) { "B 추가 이동은 −300~+300밀리초로 설정해 주세요." }
}

/**
 * Delays the later track by reading it from a later output frame instead of copying it.
 * Tracks share [rate]. With [alignedLength], both count as that long before the offset.
 */
private fun mixWithOffset(
    a: ShortArray,
    b: ShortArray,
    rate: Int,
    settings: MixSettings,
    alignedLength: Int?,
    checkActive: () -> Unit,
): ShortArray {
    val offsetFrames = (abs(settings.offsetMs).toDouble() * rate / 1_000.0).roundToInt()
    val startA = if (settings.offsetMs < 0) offsetFrames else 0
    val startB = if (settings.offsetMs > 0) offsetFrames else 0
    val lengthA = alignedLength ?: a.size
    val lengthB = alignedLength ?: b.size
    val delayedLength = if (settings.offsetMs < 0) lengthA else lengthB
    require(offsetFrames <= Int.MAX_VALUE - delayedLength) { "시작 위치를 조절한 음원이 너무 깁니다." }
    return mixPcm16(
        trackA = a,
        startA = startA,
        trackB = b,
        startB = startB,
        length = maxOf(startA + lengthA, startB + lengthB),
        // Each voice is halved (100% volume = linear gain 0.5) so the sum of two stays within PCM16 without
        // clipping; previews and saved mixes both use this gain, so a mix can sound quieter than its sources.
        gainA = settings.volumeA * 0.5f,
        gainB = settings.volumeB * 0.5f,
        checkActive = checkActive,
    )
}
