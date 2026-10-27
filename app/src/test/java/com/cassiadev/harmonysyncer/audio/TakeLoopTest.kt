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

    /**
     * Fills each requested chunk with consecutive frame numbers and records the requested counts.
     * [available] frames wait in its buffer for a non-blocking read, as audio captured during a slow write would.
     */
    private class FakeMicrophone(
        private val failAt: Int = -1,
        private val error: Int = -3,
        private var available: Int = 0,
    ) {
        val counts = mutableListOf<Int>()
        private var next: Short = 0

        fun readAvailable(buffer: ShortArray, offset: Int, count: Int): Int {
            val frames = minOf(available, count)
            for (index in 0 until frames) buffer[offset + index] = next++
            available -= frames
            return frames
        }

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
        isInterrupted: () -> Boolean = { false },
        draft: RecordingDraft? = null,
        sampleRate: Int = 8_000,
        maxFrames: Int = 1_000,
        chunkFrames: Int = 10,
        onProgress: (Long) -> Unit = {},
        firstFrame: () -> Long? = { 0L },
        onRead: (Long) -> Unit = {},
    ) = readTake(
        sampleRate,
        maxFrames,
        chunkFrames,
        microphone::read,
        microphone::readAvailable,
        shouldStop,
        isInterrupted,
        draft,
        onProgress,
        firstFrame = firstFrame,
        onRead = onRead,
    )

    private fun frames(range: LongRange) = ShortArray(range.count()) { (range.first + it).toShort() }

    @Test
    fun `drops frames before the first frame in buffer and draft`() {
        val draft = checkNotNull(RecordingDrafts(folder.root).create("take", 8_000))

        val take = readWith(FakeMicrophone(), shouldStop = stopOnCall(7), draft = draft, firstFrame = { 25L })

        assertArrayEquals(frames(25L..59L), take.clip.samples)
        assertArrayEquals(take.clip.samples, checkNotNull(draft.loadClip()).samples)
    }

    @Test
    fun `drops everything until the first frame is decided`() {
        var reads = 0
        val take = readWith(
            FakeMicrophone(),
            shouldStop = stopOnCall(7),
            onRead = { reads++ },
            firstFrame = { if (reads <= 3) null else 40L },
        )

        assertArrayEquals(frames(40L..59L), take.clip.samples)
    }

    @Test
    fun `stop before the first frame keeps nothing`() {
        val take = readWith(FakeMicrophone(), shouldStop = stopOnCall(4), firstFrame = { null })

        assertEquals(0, take.clip.samples.size)
        assertEquals(TakeStopReason.Requested, take.stopReason)
    }

    @Test
    fun `elapsed time and limit count kept frames`() {
        val progress = mutableListOf<Long>()

        val take = readWith(FakeMicrophone(), maxFrames = 50, firstFrame = { 100L }, onProgress = { progress += it })

        assertArrayEquals(frames(100L..149L), take.clip.samples)
        assertEquals(TakeStopReason.TimeLimit, take.stopReason)
        assertEquals(50L * 1_000 / 8_000, progress.last())
    }

    @Test
    fun `reports input frames after each read`() {
        val inputs = mutableListOf<Long>()

        readWith(FakeMicrophone(), shouldStop = stopOnCall(4), onRead = { inputs += it })

        assertEquals(listOf(10L, 20L, 30L), inputs)
    }

    @Test
    fun `interruption before the first frame returns an empty take`() {
        val microphone = FakeMicrophone()
        val silencedTake = readWith(microphone, isInterrupted = { microphone.counts.size >= 3 }, firstFrame = { null })
        assertEquals(0, silencedTake.clip.samples.size)
        assertEquals(TakeStopReason.Interrupted, silencedTake.stopReason)

        val failing = readWith(FakeMicrophone(failAt = 3), firstFrame = { null })
        assertEquals(0, failing.clip.samples.size)
        assertEquals(TakeStopReason.Interrupted, failing.stopReason)
    }

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
    fun `stopping keeps what the microphone already captured`() {
        val draft = checkNotNull(RecordingDrafts(folder.root).create("take", 8_000))

        val take = readWith(FakeMicrophone(available = 30), shouldStop = stopOnCall(3), draft = draft)

        assertEquals(TakeStopReason.Requested, take.stopReason)
        assertArrayEquals(ShortArray(50) { it.toShort() }, take.clip.samples)
        assertArrayEquals(take.clip.samples, checkNotNull(draft.loadClip()).samples)
    }

    @Test
    fun `the captured tail stops at the time limit`() {
        val take = readWith(FakeMicrophone(available = 30), shouldStop = stopOnCall(2), maxFrames = 25)

        assertEquals(25, take.clip.samples.size)
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
        val take = readWith(FakeMicrophone(), isInterrupted = stopOnCall(2))

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
    fun `silencing stays reported after it is lifted`() {
        val latch = TakeInterruptionLatch()
        assertFalse(latch.wasInterrupted)

        // Both callbacks can arrive while the reading thread is busy with a slow draft write.
        latch.report(interrupted = true)
        latch.report(interrupted = false)

        assertTrue(latch.wasInterrupted)
    }

    @Test
    fun `silencing before any frame is reported as unavailable`() {
        val drafts = RecordingDrafts(folder.root)
        val draft = checkNotNull(drafts.create("take", 8_000))

        val failure = assertThrows(IllegalStateException::class.java) {
            readWith(FakeMicrophone(), isInterrupted = { true }, draft = draft)
        }

        assertEquals(MICROPHONE_UNAVAILABLE_MESSAGE, failure.message)
        assertFalse(File(folder.root, "take@8000.pcm").exists())
    }

    @Test
    fun `a stop before the first frame of a silenced take is still reported as unavailable`() {
        val drafts = RecordingDrafts(folder.root)
        val draft = checkNotNull(drafts.create("take", 8_000))

        // The stop arrived while the microphone was starting; its buffer holds only silenced samples.
        val failure = assertThrows(IllegalStateException::class.java) {
            readWith(FakeMicrophone(available = 4), shouldStop = { true }, isInterrupted = { true }, draft = draft)
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
