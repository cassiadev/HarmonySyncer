package com.cassiadev.harmonysyncer.audio

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

/** `Recordings` exists for shared audio only from Android 12 (API 31); older versions use `Music`. */
internal fun chooseRecordingRelativePath(sdkInt: Int): String =
    if (sdkInt >= Build.VERSION_CODES.S) "Recordings/HarmonySyncer/" else "Music/HarmonySyncer/"

/** Publishes recordings to shared storage, where the system file picker can choose them again. */
class RecordingStore(private val resolver: ContentResolver) {
    /** Blocking; call on an IO thread. A failed save removes its hidden row before rethrowing. */
    fun save(displayName: String, wav: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, chooseRecordingRelativePath(Build.VERSION.SDK_INT))
            // Hidden from other apps until every byte is written.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("녹음 파일을 만들지 못했습니다.")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("녹음 파일을 열지 못했습니다.")
            output.use { it.write(wav) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw failure
        }
    }
}
