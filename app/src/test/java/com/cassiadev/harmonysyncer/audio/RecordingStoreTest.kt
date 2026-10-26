package com.cassiadev.harmonysyncer.audio

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingStoreTest {
    @Test
    fun `publishing counts only when exactly one row was updated`() {
        requirePublishedRow(1)
        // 0: the row is gone; -1: ContentResolver could not reach the provider.
        for (rows in listOf(0, -1, 2)) assertThrows(IOException::class.java) { requirePublishedRow(rows) }
    }

    @Test
    fun `chooses the recordings folder from android 12`() {
        assertEquals("Music/HarmonySyncer/", chooseRecordingRelativePath(29))
        assertEquals("Music/HarmonySyncer/", chooseRecordingRelativePath(30))
        assertEquals("Recordings/HarmonySyncer/", chooseRecordingRelativePath(31))
        assertEquals("Recordings/HarmonySyncer/", chooseRecordingRelativePath(37))
    }
}
