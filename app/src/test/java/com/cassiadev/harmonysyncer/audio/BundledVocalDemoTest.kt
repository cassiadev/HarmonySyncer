package com.cassiadev.harmonysyncer.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BundledVocalDemoTest {
    @Test
    fun `bundled voices share a musical reference`() {
        val aligned = BundledVocalDemo.loadAligned(::openAsset)

        assertEquals(48_000, BundledVocalDemo.SAMPLE_RATE)
        assertEquals(384_000, aligned.trackA.size)
        assertEquals(384_000, aligned.trackB.size)
        assertEquals(0, aligned.originalOffsetFrames)
        assertEquals(0.0, aligned.originalOffsetMs, 0.0)
        assertFalse(aligned.trackA.contentEquals(aligned.trackB))
    }

    @Test
    fun `bundled pair plays through the app mix path with the pinned digest`() {
        val (a, b) = BundledVocalDemo.loadSamplePair(::openAsset)
        val mixed = preparePreview(a.clip, b.clip, MixSettings())

        assertEquals(BundledVocalDemo.TRACK_A_NAME, a.name)
        assertEquals(BundledVocalDemo.TRACK_B_NAME, b.name)
        assertEquals(48_000, mixed.sampleRate)
        assertEquals(384_000, mixed.samples.size)

        // Independently calculated from the bundled PCM files with gains of 0.5 each, the gain
        // preparePreview applies at 100% volume. The digest was first pinned for the removed
        // loadMixed(); an unchanged value shows the app path produces the same mix.
        // Covers signed little-endian loading, both real inputs, rounding and the full tail.
        val bytes = ByteBuffer.allocate(mixed.samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(mixed.samples)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(
            "ccde988c3668069d82acd48c525c33e4b58febcefde86a4e7f67843824186a98",
            sha256,
        )
    }

    private fun openAsset(path: String): InputStream = checkNotNull(
        javaClass.classLoader!!.getResourceAsStream(path),
    ) { "Missing bundled vocal asset: $path" }
}
