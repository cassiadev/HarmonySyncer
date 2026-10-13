package com.cassiadev.harmonysyncer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Upsamples to the higher input rate, preserving duration and pitch without
 * discarding either recording's frequency range. A 32-tap windowed sinc filter
 * avoids the high-frequency loss of linear interpolation. Equal rates reuse the input.
 */
internal fun resamplePcmUp(clip: PcmClip, targetRate: Int, checkActive: () -> Unit): PcmClip {
    if (clip.sampleRate == targetRate) return clip
    require(targetRate > clip.sampleRate)
    val length = (clip.samples.size.toLong() * targetRate + clip.sampleRate / 2) / clip.sampleRate
    require(length in 1..Int.MAX_VALUE.toLong()) { "변환한 음원이 너무 깁니다." }

    val radius = 16
    val phases = 1_024
    val filters = Array(phases) { phase ->
        val fraction = phase.toDouble() / phases
        val weights = DoubleArray(radius * 2) { tap ->
            val distance = tap - radius + 1 - fraction
            val sinc = if (abs(distance) < 1e-12) 1.0 else sin(PI * distance) / (PI * distance)
            val window = 0.42 + 0.5 * cos(PI * distance / radius) +
                0.08 * cos(2 * PI * distance / radius)
            sinc * window
        }
        val sum = weights.sum()
        FloatArray(weights.size) { (weights[it] / sum).toFloat() }
    }
    val output = ShortArray(length.toInt())
    for (frame in output.indices) {
        if (frame % 2_048 == 0) checkActive()
        val position = frame.toLong() * clip.sampleRate
        val base = (position / targetRate).toInt()
        val phase = ((position % targetRate) * phases / targetRate).toInt()
        val weights = filters[phase]
        var sample = 0.0
        for (tap in weights.indices) {
            val source = (base + tap - radius + 1).coerceIn(clip.samples.indices)
            sample += clip.samples[source] * weights[tap]
        }
        output[frame] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return PcmClip(output, targetRate)
}
