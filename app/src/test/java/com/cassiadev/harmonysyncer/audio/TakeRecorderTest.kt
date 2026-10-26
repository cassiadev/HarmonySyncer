package com.cassiadev.harmonysyncer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TakeRecorderTest {
    @Test
    fun `capture buffer holds one second unless the device needs more`() {
        assertEquals(96_000, chooseCaptureBufferBytes(48_000, minBufferBytes = 3_840))
        assertEquals(88_200, chooseCaptureBufferBytes(44_100, minBufferBytes = 3_528))
        assertEquals(200_000, chooseCaptureBufferBytes(48_000, minBufferBytes = 200_000))
    }
}
