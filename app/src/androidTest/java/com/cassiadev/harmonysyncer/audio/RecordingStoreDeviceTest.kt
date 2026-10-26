package com.cassiadev.harmonysyncer.audio

import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saves through the real MediaStore so the folder, visibility and bytes match what the picker sees. */
class RecordingStoreDeviceTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Test
    fun savedRecordingReadsBackFromTheVersionFolder() {
        val samples = shortArrayOf(1, 2, 3, 4)
        val name = "HarmonySyncer-test-${System.nanoTime()}.wav"
        val uri = RecordingStore(resolver).save(name, encodeWavPcm16(PcmClip(samples, 48_000)))
        try {
            val clip = checkNotNull(resolver.openInputStream(uri)).use { readWavPcm16(it) }
            assertArrayEquals(samples, clip.samples)
            assertEquals(48_000, clip.sampleRate)

            val columns = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING)
            checkNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(chooseRecordingRelativePath(Build.VERSION.SDK_INT), cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
