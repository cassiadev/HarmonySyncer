package com.cassiadev.harmonysyncer.audio

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records a metronome take whose first sample is the count-in's first beat T. Clicks, if any, run on
 * [clickDispatcher] and are armed once the recorder has a capture stamp; T is the time they report, or
 * [COUNT_IN_LEAD_NANOS] from then without clicks or when they fail first. However the take ends, the clicks
 * are told to stop and are waited for: they run a blocking loop that cancellation does not end.
 * [recordTake] may block its thread, so call this on a multi-threaded dispatcher such as IO: the count-in is
 * decided on another thread meanwhile.
 */
suspend fun recordWithCountIn(
    clickDispatcher: CoroutineDispatcher,
    recordTake: suspend (TakeAnchor) -> RecordedTake,
    playClicks: (suspend (isArmed: () -> Boolean, shouldStop: () -> Boolean, onCountInStart: (Long) -> Unit) -> Unit)?,
    nanoTime: () -> Long,
    onCountInStart: (Long) -> Unit,
): RecordedTake = coroutineScope {
    val anchor = TakeAnchor()
    val armed = AtomicBoolean(false)
    val done = AtomicBoolean(false)
    val clickStart = CompletableDeferred<Long>()
    val clicks = playClicks?.let { play ->
        launch(clickDispatcher) {
            try {
                play(armed::get, done::get) { clickStart.complete(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // A take goes on without clicks, the beats shown on screen only.
                clickStart.completeExceptionally(failure)
            } finally {
                clickStart.completeExceptionally(IllegalStateException("The click track ended before the count-in"))
            }
        }
    }
    val coordinator = launch {
        val stamp = anchor.stamp.await()
        armed.set(true)
        val start = if (clicks == null) {
            nanoTime() + COUNT_IN_LEAD_NANOS
        } else {
            try {
                clickStart.await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                nanoTime() + COUNT_IN_LEAD_NANOS
            }
        }
        anchor.firstFrame = frameAt(start, stamp.time, stamp.sampleRate)
        onCountInStart(start)
    }
    try {
        recordTake(anchor)
    } finally {
        done.set(true)
        coordinator.cancel()
        withContext(NonCancellable) { clicks?.join() }
    }
}
