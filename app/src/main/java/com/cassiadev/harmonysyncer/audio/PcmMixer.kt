package com.cassiadev.harmonysyncer.audio

import kotlin.math.roundToInt

/**
 * Mixes PCM16 tracks with matching sample rates and channel layouts into a new buffer.
 * Missing samples are silence; empty inputs are supported. Inputs are never modified.
 *
 * Default gains average the tracks. Higher gains can clip, so samples are rounded
 * (ties toward positive infinity) and saturated to the PCM16 range to prevent wraparound.
 *
 * @param trackA First PCM16 sample buffer. May be empty; samples are mixed by matching index.
 * @param trackB Second PCM16 sample buffer. May be empty and must use the same sample rate
 * and channel layout as [trackA].
 * @param gainA Linear amplitude multiplier for [trackA]. Must be finite and non-negative.
 * Defaults to `0.5f` (half amplitude); `0f` mutes this track and `1f` keeps its amplitude unchanged.
 * @param gainB Linear amplitude multiplier for [trackB]. Must be finite and non-negative.
 * Defaults to `0.5f` (half amplitude); `0f` mutes this track and `1f` keeps its amplitude unchanged.
 * @return A new PCM16 buffer as long as the longer input, with missing input samples treated
 * as silence. Returns an empty array when both inputs are empty.
 * @throws IllegalArgumentException if either gain is negative or not finite.
 */
fun mixPcm16(
    trackA: ShortArray,
    trackB: ShortArray,
    gainA: Float = 0.5f,
    gainB: Float = 0.5f,
): ShortArray = mixPcm16(trackA, 0, trackB, 0, maxOf(trackA.size, trackB.size), gainA, gainB)

/**
 * Mixes [length] output frames, reading [trackA] from output frame [startA] and [trackB]
 * from [startB]. Frames outside either track are silence, so offsets need no padded copies.
 */
internal fun mixPcm16(
    trackA: ShortArray,
    startA: Int,
    trackB: ShortArray,
    startB: Int,
    length: Int,
    gainA: Float,
    gainB: Float,
    checkActive: () -> Unit = {},
): ShortArray {
    require(gainA.isFinite() && gainA >= 0f) { "gainA must be finite and non-negative" }
    require(gainB.isFinite() && gainB >= 0f) { "gainB must be finite and non-negative" }

    return ShortArray(length) { index ->
        if (index % 2_048 == 0) checkActive()
        val sampleA = trackA.getOrElse(index - startA) { 0 }
        val sampleB = trackB.getOrElse(index - startB) { 0 }

        // Double keeps even very large finite Float gains from overflowing during mixing.
        (sampleA * gainA.toDouble() + sampleB * gainB.toDouble())
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}
