package com.cassiadev.harmonysyncer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStoreTest {
    @Test
    fun `chooses the recordings folder from android 12`() {
        assertEquals("Music/HarmonySyncer/", chooseRecordingRelativePath(29))
        assertEquals("Music/HarmonySyncer/", chooseRecordingRelativePath(30))
        assertEquals("Recordings/HarmonySyncer/", chooseRecordingRelativePath(31))
        assertEquals("Recordings/HarmonySyncer/", chooseRecordingRelativePath(37))
    }
}
