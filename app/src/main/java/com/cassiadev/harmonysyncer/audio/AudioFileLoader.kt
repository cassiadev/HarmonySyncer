package com.cassiadev.harmonysyncer.audio

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class LoadedAudioFile(val name: String, val clip: PcmClip)

/** Loads a user-selected document using its URI grant, without storage permissions. */
class AudioFileLoader(private val resolver: ContentResolver) {
    suspend fun load(uri: Uri): LoadedAudioFile = withContext(Dispatchers.IO) {
        ensureActive()
        val name = displayName(uri)
        ensureActive()
        val clip = try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("선택한 파일을 열 수 없습니다.")
            input.use(::readWavPcm16)
        } catch (exception: SecurityException) {
            throw IllegalArgumentException("파일에 접근할 수 없습니다. 파일을 다시 선택해 주세요.", exception)
        } catch (exception: IOException) {
            throw IllegalArgumentException("파일을 읽을 수 없습니다. 파일을 다시 선택해 주세요.", exception)
        }
        ensureActive()
        LoadedAudioFile(name, clip)
    }

    private fun displayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "선택한 오디오.wav"
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) {
                    cursor.getString(column)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } ?: fallback
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Some document providers offer stream access without metadata queries.
            fallback
        }
    }
}
