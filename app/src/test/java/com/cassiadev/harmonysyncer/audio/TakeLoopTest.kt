package com.cassiadev.harmonysyncer.audio

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakeLoopTest {
    @get:Rule val folder = TemporaryFolder()

    /** Fills each requested chunk with consecutive frame numbers and records the requested counts. */
    private class FakeMicrophone(private val failAt: Int = -1, private val error: Int = -3) {
        val counts = mutableListOf<Int>()
        private var next: Short = 0

        fun read(buffer: ShortArray, offset: Int, count: Int): Int {
            if (counts.size == failAt) return error
            counts += count
            for (index in 0 until count) buffer[offset + index] = next++
            return count
        }
    }

    private fun stopOnCall(call: Int): () -> Boolean {
        var calls = 0
        return { ++calls >= call }
    }

    private fun readWith(
        microphone: FakeMicrophone,
        shouldStop: () -> Boolean = { false },
        isSilenced: () -> Boolean = { false },
        draft: RecordingDraft? = null,
        sampleRate: Int = 8_000,
        maxFrames: Int = 1_000,
        chunkFrames: Int = 10,
        onProgress: (Long) -> Unit = {},
    ) = readTake(sampleRate, maxFrames, chunkFrames, microphone::read, shouldStop, isSilenced, draft, onProgress)

    @Test
    fun `stops when asked and keeps every frame in the draft`() {
        val draft = checkNotNull(RecordingDrafts(folder.root).create("take", 8_000))

        val take = readWith(FakeMicrophone(), shouldStop = stopOnCall(3), draft = draft)

        assertEquals(TakeStopReason.Requested, take.stopReason)
        assertArrayEquals(ShortArray(20) { it.toShort() }, take.clip.samples)
        assertEquals(8_000, take.clip.sampleRate)
        assertArrayEquals(take.clip.samples, checkNotNull(draft.loadClip()).samples)
    }

    @Test
    fun `stops at the time limit`() {
        val microphone = FakeMicrophone()

        val take = readWith(microphone, maxFrames = 25)

        assertEquals(TakeStopReason.TimeLimit, take.stopReason)
        assertEquals(25, take.clip.samples.size)
        assertEquals(listOf(10, 10, 5), microphone.counts)
    }

    @Test
    fun `silencing interrupts and keeps frames`() {
        val take = readWith(FakeMicrophone(), isSilenced = stopOnCall(2))

        assertEquals(TakeStopReason.Interrupted, take.stopReason)
        assertEquals(10, take.clip.samples.size)
    }

    @Test
    fun `read error before any frame is reported as unavailable`() {
        val drafts = RecordingDrafts(folder.root)
        val draft = checkNotNull(drafts.create("take", 8_000))

        val failure = assertThrows(IllegalStateException::class.java) {
            readWith(FakeMicrophone(failAt = 0), draft = draft)
        }

        assertEquals(MICROPHONE_UNAVAILABLE_MESSAGE, failure.message)
        val file = File(folder.root, "take@8000.pcm")
        assertFalse(file.exists())
        // Released: a new file at the same path is claimable again.
        file.writeBytes(byteArrayOf(1, 0))
        assertEquals(1, drafts.claimLeftovers().size)
    }

    @Test
    fun `silencing before any frame is reported as unavailable`() {
        val drafts = RecordingDrafts(folder.root)
        val draft = checkNotNull(drafts.create("take", 8_000))

        val failure = assertThrows(IllegalStateException::class.java) {
            readWith(FakeMicrophone(), isSilenced = { true }, draft = draft)
        }

        assertEquals(MICROPHONE_UNAVAILABLE_MESSAGE, failure.message)
        assertFalse(File(folder.root, "take@8000.pcm").exists())
    }

    @Test
    fun `read error after frames keeps them`() {
        val take = readWith(FakeMicrophone(failAt = 1))

        assertEquals(TakeStopReason.Interrupted, take.stopReason)
        assertEquals(10, take.clip.samples.size)
    }

    @Test
    fun `keeps recording when the draft fails`() {
        val draft = checkNotNull(RecordingDrafts(folder.root).create("take", 8_000))
        draft.close()

        val take = readWith(FakeMicrophone(), shouldStop = stopOnCall(3), draft = draft)

        assertEquals(20, take.clip.samples.size)
        assertFalse(draft.isIntact)
    }

    @Test
    fun `reports elapsed milliseconds`() {
        val reported = mutableListOf<Long>()

        readWith(FakeMicrophone(), shouldStop = stopOnCall(7), sampleRate = 1_000, chunkFrames = 50) { reported += it }

        // The first chunk is reported at once, so the screen can tell that audio arrives.
        assertEquals(50L, reported.first())
        assertTrue(reported.toString(), reported.containsAll(listOf(100L, 200L, 300L)))
        assertEquals(reported.sorted(), reported)
        assertEquals(300L, reported.last())
    }
}
