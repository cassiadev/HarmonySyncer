package com.cassiadev.harmonysyncer.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingDraftsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `appended samples load back`() {
        val drafts = RecordingDrafts(folder.root)
        val draft = checkNotNull(drafts.create("HarmonySyncer-A-20261026-153012", 48_000))

        assertTrue(File(folder.root, "HarmonySyncer-A-20261026-153012@48000.pcm").isFile)
        assertEquals("HarmonySyncer-A-20261026-153012.wav", draft.displayName)
        assertTrue(draft.append(shortArrayOf(9, 1, -2, 3), 1, 3))
        draft.close()
        val clip = checkNotNull(draft.loadClip())
        assertArrayEquals(shortArrayOf(1, -2, 3), clip.samples)
        assertEquals(48_000, clip.sampleRate)
    }

    @Test
    fun `does not overwrite an existing draft`() {
        val drafts = RecordingDrafts(folder.root)
        val first = checkNotNull(drafts.create("take", 48_000))
        first.append(shortArrayOf(5), 0, 1)
        first.close()

        val second = checkNotNull(drafts.create("take", 48_000))

        assertEquals("take-2.wav", second.displayName)
        assertArrayEquals(shortArrayOf(5), checkNotNull(first.loadClip()).samples)
    }

    @Test
    fun `claimed drafts are skipped until released`() {
        val drafts = RecordingDrafts(folder.root)
        val draft = checkNotNull(drafts.create("take", 8_000))

        assertTrue(drafts.claimLeftovers().isEmpty())
        draft.release()
        assertEquals(listOf("take.wav"), drafts.claimLeftovers().map { it.displayName })
    }

    @Test
    fun `ignores names it cannot parse`() {
        val names = listOf("junk.pcm", "x@abc.pcm", "x@0.pcm", "x@999999.pcm")
        for (name in names) File(folder.root, name).writeBytes(byteArrayOf(1, 0))

        assertTrue(RecordingDrafts(folder.root).claimLeftovers().isEmpty())
        for (name in names) assertTrue(name, File(folder.root, name).isFile)
    }

    @Test
    fun `drops a trailing odd byte`() {
        File(folder.root, "x@8000.pcm").writeBytes(byteArrayOf(1, 0, 2))

        val draft = RecordingDrafts(folder.root).claimLeftovers().single()

        assertArrayEquals(shortArrayOf(1), checkNotNull(draft.loadClip()).samples)
        assertEquals(8_000, draft.sampleRate)
    }

    @Test
    fun `an empty draft loads no clip`() {
        File(folder.root, "x@8000.pcm").writeBytes(byteArrayOf(7))

        assertNull(RecordingDrafts(folder.root).claimLeftovers().single().loadClip())
    }

    @Test
    fun `append after close fails and marks the draft broken`() {
        val draft = checkNotNull(RecordingDrafts(folder.root).create("take", 8_000))
        assertTrue(draft.isIntact)

        draft.close()

        assertFalse(draft.append(shortArrayOf(1), 0, 1))
        assertFalse(draft.isIntact)
    }

    @Test
    fun `recovery saves, deletes saved drafts and keeps failed ones`() = runTest {
        File(folder.root, "ok@8000.pcm").writeBytes(encodeLittleEndian(1, 2))
        File(folder.root, "fail@8000.pcm").writeBytes(encodeLittleEndian(3))
        File(folder.root, "empty@8000.pcm").writeBytes(ByteArray(0))
        val drafts = RecordingDrafts(folder.root)
        val saved = mutableMapOf<String, ByteArray>()

        val count = saveLeftoverDrafts(drafts) { name, wav ->
            check(name != "fail.wav") { "storage full" }
            saved[name] = wav
        }

        assertEquals(1, count)
        assertEquals(setOf("ok.wav"), saved.keys)
        assertArrayEquals(shortArrayOf(1, 2), readWavPcm16(saved.getValue("ok.wav").inputStream()).samples)
        assertFalse(File(folder.root, "ok@8000.pcm").exists())
        assertFalse(File(folder.root, "empty@8000.pcm").exists())
        assertTrue(File(folder.root, "fail@8000.pcm").isFile)
        assertEquals(listOf("fail.wav"), drafts.claimLeftovers().map { it.displayName })
    }

    private fun encodeLittleEndian(vararg samples: Short): ByteArray {
        val bytes = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        return bytes.array()
    }
}
