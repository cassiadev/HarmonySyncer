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
    fun `bundled voices share a musical reference and mix all eight seconds`() {
        val aligned = BundledVocalDemo.loadAligned(::openAsset)
        val mixed = BundledVocalDemo.loadMixed(::openAsset)

        assertEquals(48_000, BundledVocalDemo.SAMPLE_RATE)
        assertEquals(384_000, aligned.trackA.size)
        assertEquals(384_000, aligned.trackB.size)
        assertEquals(384_000, mixed.size)
        assertEquals(0, aligned.originalOffsetFrames)
        assertEquals(0.0, aligned.originalOffsetMs, 0.0)
        assertFalse(aligned.trackA.contentEquals(aligned.trackB))

        // Independently calculated from the bundled PCM files with gains of 0.5 each.
        // Covers signed little-endian loading, both real inputs, rounding and the full tail.
        val bytes = ByteBuffer.allocate(mixed.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(mixed)
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
