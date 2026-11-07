package com.cassiadev.harmonysyncer.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal const val TIMING_SIGNAL_SEED = 20_261_107L

private const val WINDOW_SECONDS = 2
private const val SOUND_LEVEL = 0.1
private const val GUARD_MS = 5
// First guess: the best match must stand out twice as much as any other position.
private const val MIN_PEAK_RATIO = 2.0
// First guess: real clocks differ far less, so a wider gap means the windows matched different events.
private const val MAX_SPACING_ERROR = 0.01

/**
 * [centerFrame] is the middle of the window in A. [offsetFrames] is B's time minus A's for the sound in this
 * window, so a later B is positive. [peakRatio] compares the best match with the best match elsewhere.
 */
internal data class WindowOffset(val centerFrame: Int, val offsetFrames: Int, val peakRatio: Double)

/**
 * Offsets at the start, middle and end of A's sound, at [sampleRate]. [isCertain] needs all three to stand out
 * and to sit as far apart in B as they do in A, so windows cannot all lean on one event.
 */
internal data class OffsetEstimate(
    val sampleRate: Int,
    val windows: List<WindowOffset>,
    val growthMsPerMinute: Double,
    val isCertain: Boolean,
)

/** 100 ms noise bursts at irregular gaps of 200 to 600 ms, so no two positions look alike. */
internal fun buildTimingSignal(seconds: Int, rate: Int, seed: Long = TIMING_SIGNAL_SEED): ShortArray {
    val random = Random(seed)
    val samples = ShortArray(seconds * rate)
    var frame = 0
    while (frame < samples.size) {
        val end = minOf(frame + rate / 10, samples.size)
        for (index in frame until end) samples[index] = (random.nextInt(24_001) - 12_000).toShort()
        frame = end + rate * (200 + random.nextInt(401)) / 1_000
    }
    return samples
}

/** Slides windows of A's sound along all of B. Clips at different rates are compared at the higher one. */
internal fun estimateTimeOffsets(a: PcmClip, b: PcmClip, windowCount: Int = 3): OffsetEstimate {
    val (normalizedA, normalizedB) = normalizePreviewRates(a, b)
    val rate = normalizedA.sampleRate
    val windowFrames = WINDOW_SECONDS * rate
    val starts = findWindowStarts(normalizedA.samples, windowFrames, windowCount)
    if (starts.isEmpty()) return OffsetEstimate(rate, listOf(WindowOffset(0, 0, 0.0)), 0.0, false)

    val size = (normalizedB.samples.size + windowFrames).takeHighestOneBit() * 2
    val fourier = Fourier(size)
    val targetRe = DoubleArray(size)
    val targetIm = DoubleArray(size)
    val targetMean = normalizedB.samples.average()
    normalizedB.samples.forEachIndexed { index, sample -> targetRe[index] = sample - targetMean }
    fourier.transform(targetRe, targetIm)

    val windows = starts.map { start ->
        val end = minOf(start + windowFrames, normalizedA.samples.size)
        val window = normalizedA.samples.copyOfRange(start, end)
        val correlation = correlate(window, targetRe, targetIm, fourier)
        var best = 0
        for (index in correlation.indices) if (abs(correlation[index]) > abs(correlation[best])) best = index
        val guard = rate * GUARD_MS / 1_000
        var rival = 0.0
        for (index in correlation.indices) {
            val distance = abs(index - best)
            if (minOf(distance, size - distance) > guard) rival = maxOf(rival, abs(correlation[index]))
        }
        val lag = if (best < normalizedB.samples.size) best else best - size
        val peak = abs(correlation[best])
        WindowOffset(start + window.size / 2, lag - start, if (peak == 0.0) 0.0 else peak / rival)
    }
    val spanFrames = windows.last().centerFrame - windows.first().centerFrame
    val growthFrames = windows.last().offsetFrames - windows.first().offsetFrames
    return OffsetEstimate(
        sampleRate = rate,
        windows = windows,
        growthMsPerMinute = if (spanFrames == 0) 0.0 else growthFrames * 60_000.0 / spanFrames,
        isCertain = windows.all { it.peakRatio >= MIN_PEAK_RATIO } && keepsSpacing(windows),
    )
}

/**
 * Growth over a minute from the windows that stand out: the median slope between every pair of them, so a window
 * that matched the wrong place cannot pull it. Null unless more than half of the windows stand out.
 */
internal fun fitGrowthMsPerMinute(windows: List<WindowOffset>): Double? {
    val clear = windows.filter { it.peakRatio >= MIN_PEAK_RATIO }
    if (clear.size * 2 <= windows.size) return null
    val slopes = clear.flatMapIndexed { index, earlier ->
        clear.drop(index + 1).map { later ->
            (later.offsetFrames - earlier.offsetFrames).toDouble() / (later.centerFrame - earlier.centerFrame)
        }
    }.sorted()
    return (slopes[(slopes.size - 1) / 2] + slopes[slopes.size / 2]) / 2 * 60_000
}

private fun keepsSpacing(windows: List<WindowOffset>): Boolean = windows.zipWithNext().all { (earlier, later) ->
    abs(later.offsetFrames - earlier.offsetFrames) <= MAX_SPACING_ERROR * (later.centerFrame - earlier.centerFrame)
}

/** Where [windowCount] windows spread from the start to the end of the sound begin, or nothing for silence. */
private fun findWindowStarts(samples: ShortArray, windowFrames: Int, windowCount: Int): List<Int> {
    val level = samples.maxOf { abs(it.toInt()) } * SOUND_LEVEL
    if (level == 0.0) return emptyList()
    val first = samples.indexOfFirst { abs(it.toInt()) >= level }
    val last = samples.indexOfLast { abs(it.toInt()) >= level }
    val lastStart = (last + 1 - windowFrames).coerceAtLeast(first)
    return List(windowCount) { first + (lastStart - first) * it / (windowCount - 1) }
}

/** Correlation of [window] with the target at every lag; a lag past the target's end wraps to negative. */
private fun correlate(window: ShortArray, targetRe: DoubleArray, targetIm: DoubleArray, fourier: Fourier): DoubleArray {
    val re = DoubleArray(targetRe.size)
    val im = DoubleArray(targetRe.size)
    val mean = window.average()
    window.forEachIndexed { index, sample -> re[index] = sample - mean }
    fourier.transform(re, im)
    for (index in re.indices) {
        val productRe = re[index] * targetRe[index] + im[index] * targetIm[index]
        im[index] = re[index] * targetIm[index] - im[index] * targetRe[index]
        re[index] = productRe
    }
    // Swapping the parts turns the forward transform into the inverse; only ratios matter, so no scaling.
    fourier.transform(im, re)
    return re
}

/** Forward radix-2 transform of a fixed power-of-two [size]. */
private class Fourier(private val size: Int) {
    private val cosines = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { -sin(2 * PI * it / size) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        reorderBits(re, im)
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            for (start in 0 until size step length) {
                for (offset in 0 until half) {
                    val low = start + offset
                    val high = low + half
                    val turnRe = cosines[offset * step]
                    val turnIm = sines[offset * step]
                    val partRe = re[high] * turnRe - im[high] * turnIm
                    val partIm = re[high] * turnIm + im[high] * turnRe
                    re[high] = re[low] - partRe
                    im[high] = im[low] - partIm
                    re[low] += partRe
                    im[low] += partIm
                }
            }
            length *= 2
        }
    }

    private fun reorderBits(re: DoubleArray, im: DoubleArray) {
        var target = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (target and bit != 0) {
                target = target xor bit
                bit = bit shr 1
            }
            target = target xor bit
            if (index < target) {
                re[index] = re[target].also { re[target] = re[index] }
                im[index] = im[target].also { im[target] = im[index] }
            }
        }
    }
}
