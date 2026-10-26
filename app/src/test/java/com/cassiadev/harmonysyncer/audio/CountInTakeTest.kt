package com.cassiadev.harmonysyncer.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CountInTakeTest {
    private val stamp = CaptureStamp(48_000, FrameTime(0, 0), measured = true)
    private val take = RecordedTake(PcmClip(shortArrayOf(1), 48_000), TakeStopReason.Requested, null)

    /** Stands in for the recorder: reports [stamp] (unless null), records for a virtual second and returns [result]. */
    private class FakeTake(private val stamp: CaptureStamp?, private val result: RecordedTake) {
        var anchor: TakeAnchor? = null

        suspend fun record(anchor: TakeAnchor): RecordedTake {
            this.anchor = anchor
            stamp?.let { anchor.stamp.complete(it) }
            // Long enough for the coordinator and the clicks to run on the shared test dispatcher.
            delay(1_000)
            return result
        }
    }

    @Test
    fun `without clicks the count-in starts one lead after the stamp`() = runTest {
        val fake = FakeTake(stamp, take)
        val starts = mutableListOf<Long>()

        val result = recordWithCountIn(StandardTestDispatcher(testScheduler), fake::record, null, { 1_000_000_000L }) { starts += it }

        assertSame(take, result)
        assertEquals(listOf(1_150_000_000L), starts)
        assertEquals(55_200L, fake.anchor?.firstFrame)
    }

    @Test
    fun `clicks are armed only after the capture stamp`() = runTest {
        val fake = FakeTake(stamp, take)
        val armedBeforeStamp = mutableListOf<Boolean>()
        var armedAtStart = false
        var stopped = false

        recordWithCountIn(
            StandardTestDispatcher(testScheduler),
            { anchor ->
                // Runs the clicks before the stamp exists.
                delay(10)
                fake.record(anchor)
            },
            { isArmed, shouldStop, onCountInStart ->
                armedBeforeStamp += isArmed()
                while (!isArmed()) delay(1)
                armedAtStart = true
                onCountInStart(2_000_000_000)
                while (!shouldStop()) delay(10)
                stopped = true
            },
            { 0L },
        ) {}

        assertEquals(listOf(false), armedBeforeStamp)
        assertTrue(armedAtStart)
        assertTrue(stopped)
        assertEquals(frameAt(2_000_000_000, stamp.time, 48_000), fake.anchor?.firstFrame)
    }

    @Test
    fun `a failing click track falls back to the lead`() = runTest {
        val fake = FakeTake(stamp, take)
        val starts = mutableListOf<Long>()

        val result = recordWithCountIn(
            StandardTestDispatcher(testScheduler),
            fake::record,
            { _, _, _ -> throw IllegalStateException("이 기기에서 클릭음을 재생할 수 없습니다.") },
            { 3_000_000_000L },
        ) { starts += it }

        assertSame(take, result)
        assertEquals(listOf(3_150_000_000L), starts)
    }

    @Test
    fun `a failing take still stops the clicks`() = runTest {
        var stoppedBeforeRethrow = false
        var clickEnded = false
        val failure = runCatching {
            recordWithCountIn(
                StandardTestDispatcher(testScheduler),
                {
                    delay(100)
                    throw IllegalStateException(MICROPHONE_UNAVAILABLE_MESSAGE)
                },
                { _, shouldStop, _ ->
                    while (!shouldStop()) delay(10)
                    stoppedBeforeRethrow = true
                    delay(50)
                    clickEnded = true
                },
                { 0L },
            ) {}
        }.exceptionOrNull()

        assertEquals(MICROPHONE_UNAVAILABLE_MESSAGE, failure?.message)
        assertTrue(stoppedBeforeRethrow)
        assertTrue("returned before the click track ended", clickEnded)
    }

    @Test
    fun `a take that ends before the count-in keeps no first frame`() = runTest {
        val fake = FakeTake(stamp = null, take)
        var started = false
        var clickEnded = false

        recordWithCountIn(
            StandardTestDispatcher(testScheduler),
            fake::record,
            { isArmed, shouldStop, _ ->
                while (!shouldStop()) delay(10)
                assertFalse(isArmed())
                delay(50)
                clickEnded = true
            },
            { 0L },
        ) { started = true }

        assertNull(fake.anchor?.firstFrame)
        assertFalse(started)
        assertTrue(clickEnded)
    }
}
