package com.cassiadev.harmonysyncer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the current output of both render paths so refactoring cannot change a sample.
 * Expected hashes were generated from the implementation at commit 56e0378.
 */
// shortcut: hashes also pin this JDK's sin/cos rounding in the resampler; regenerate only if a JDK change alone breaks them.
class PreviewGoldenTest {
    @Test
    fun `render path output is unchanged`() {
        assertEquals(
            mapOf(
                "48000/48000" to "fd3726b2721d89ec7d008ad789834468394868ab7c019c9b257e9a8b6eae3c02",
                "44100/48000" to "931ab389b898ae99107b60cc5599deca7bc026504cf6418938465f6b585ee9d1",
                "22050/192000" to "69ed8fc8df299b6f1594869fa5d28494965257e91782dd4a3b599baacdc8d449",
            ),
            RATE_PAIRS.associate { (rateA, rateB) ->
                "$rateA/$rateB" to digest(rateA, rateB) { a, b, settings -> renderPreview(a, b, settings) }
            },
        )
    }

    @Test
    fun `prepare path output is unchanged`() {
        assertEquals(
            mapOf(
                "48000/48000" to "964f3293a889386050a045b3ef685e4c50217b784310372907612e9e2c966163",
                "44100/48000" to "d49fcbdaac723b93a73ed60e19f8f46da8751a7efd50ab562953fba4b4ee20ec",
                "22050/192000" to "526f8278ff4427dfbd1ea3b36fad62d8d07288ea6eea2fabbfce0d950eb6087b",
            ),
            RATE_PAIRS.associate { (rateA, rateB) ->
                "$rateA/$rateB" to digest(rateA, rateB) { a, b, settings -> preparePreview(a, b, settings) }
            },
        )
    }

    private fun digest(rateA: Int, rateB: Int, render: (PcmClip, PcmClip, MixSettings) -> PcmClip): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        // Both length orders matter: the two paths pad the shorter track differently.
        for ((millisA, millisB) in listOf(20 to 13, 13 to 20)) {
            val a = PcmClip(noise(rateA * millisA / 1_000, seed = rateA), rateA)
            val b = PcmClip(noise(rateB * millisB / 1_000, seed = rateB + 1), rateB)
            for (offsetMs in intArrayOf(-300, -137, 0, 1, 250, 300)) {
                for ((volumeA, volumeB) in listOf(1f to 1f, 0.3f to 0.7f, 1f to 0f)) {
                    val result = render(a, b, MixSettings(volumeA, volumeB, offsetMs))
                    val bytes = ByteBuffer.allocate(8 + result.samples.size * Short.SIZE_BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(result.sampleRate)
                        .putInt(result.samples.size)
                    bytes.asShortBuffer().put(result.samples)
                    sha256.update(bytes.array())
                }
            }
        }
        return sha256.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Full-scale pseudo-random samples from a fixed integer recurrence, independent of library RNGs. */
    private fun noise(frames: Int, seed: Int): ShortArray {
        var state = seed
        return ShortArray(frames) {
            state = state * 1_103_515_245 + 12_345
            (state shr 16).toShort()
        }
    }

    private companion object {
        val RATE_PAIRS = listOf(48_000 to 48_000, 44_100 to 48_000, 22_050 to 192_000)
    }
}
