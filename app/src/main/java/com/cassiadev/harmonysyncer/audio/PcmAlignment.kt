package com.cassiadev.harmonysyncer.audio

/**
 * Equal-length mono PCM16 tracks with both supplied onsets at index zero.
 * Offsets describe the inputs before alignment. One frame equals one sample for mono audio.
 * Equality and hashing include array contents; changing samples can change the hash code.
 *
 * @property trackA Aligned PCM16 samples from the first track, with any missing tail
 * padded with silence (`0`).
 * @property trackB Aligned PCM16 samples from the second track, with any missing tail
 * padded with silence (`0`).
 * @property originalOffsetFrames Difference between the original onset indices (`onsetB - onsetA`).
 * A positive value means B has more samples before its onset than A; a negative value means fewer.
 * @property originalOffsetMs The same original onset difference converted to milliseconds
 * using the input sample rate. For example, 960 frames at 48,000 Hz equal 20.0 ms.
 */
data class AlignedTracks(
    val trackA: ShortArray,
    val trackB: ShortArray,
    val originalOffsetFrames: Int,
    val originalOffsetMs: Double,
) {
    /**
     * Compares both tracks by sample content and compares both original offsets.
     *
     * @param other Object to compare with this alignment result; may be `null`.
     * @return `true` if [other] is an [AlignedTracks] with equal sample arrays and offsets.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlignedTracks) return false

        return trackA.contentEquals(other.trackA) &&
            trackB.contentEquals(other.trackB) &&
            originalOffsetFrames == other.originalOffsetFrames &&
            originalOffsetMs.compareTo(other.originalOffsetMs) == 0
    }

    /**
     * Computes a hash from both sample arrays and both original offsets.
     * Kept explicit to satisfy Android Studio's ArrayInDataClass inspection.
     *
     * @return A hash code consistent with [equals] for the current sample contents and offsets.
     */
    override fun hashCode(): Int {
        var result = trackA.contentHashCode()
        result = 31 * result + trackB.contentHashCode()
        result = 31 * result + originalOffsetFrames
        result = 31 * result + originalOffsetMs.hashCode()
        return result
    }
}

/**
 * Removes samples before each onset and pads the shorter remainder with silence.
 * Both inputs must be mono PCM16 at [sampleRate]; one sample is one frame.
 * Onsets are supplied manually. Input arrays are never modified or reused as output.
 *
 * @param trackA Original mono PCM16 samples for the first track. Must not be empty.
 * @param onsetA Zero-based sample index in [trackA] to use as its sound onset.
 * Must be a valid index in [trackA]; samples before this index are discarded from the output.
 * @param trackB Original mono PCM16 samples for the second track. Must not be empty.
 * @param onsetB Zero-based sample index in [trackB] to use as its sound onset.
 * Must be a valid index in [trackB]; samples before this index are discarded from the output.
 * @param sampleRate Shared sample rate of both inputs in frames per second (Hz).
 * Must be positive; used to convert the original onset difference to milliseconds.
 * @return An [AlignedTracks] containing new arrays of equal length with both onsets at index
 * zero, plus the original onset difference in frames and milliseconds.
 * @throws IllegalArgumentException if an onset is outside its track (including an
 * empty track), or [sampleRate] is not positive.
 */
fun alignFromOnsets(
    trackA: ShortArray,
    onsetA: Int,
    trackB: ShortArray,
    onsetB: Int,
    sampleRate: Int,
): AlignedTracks {
    require(onsetA in trackA.indices) { "onsetA must be a valid index in trackA" }
    require(onsetB in trackB.indices) { "onsetB must be a valid index in trackB" }
    require(sampleRate > 0) { "sampleRate must be positive" }

    val length = maxOf(trackA.size - onsetA, trackB.size - onsetB)
    val alignedA = ShortArray(length)
    val alignedB = ShortArray(length)
    trackA.copyInto(alignedA, startIndex = onsetA)
    trackB.copyInto(alignedB, startIndex = onsetB)

    val offsetFrames = onsetB - onsetA
    return AlignedTracks(
        trackA = alignedA,
        trackB = alignedB,
        originalOffsetFrames = offsetFrames,
        originalOffsetMs = offsetFrames * 1_000.0 / sampleRate,
    )
}
