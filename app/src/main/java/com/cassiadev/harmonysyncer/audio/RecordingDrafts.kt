package com.cassiadev.harmonysyncer.audio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException

private val DRAFT_FILE_NAME = Regex("""(.+)@(\d+)\.pcm""")
private const val MAX_NAME_SUFFIX = 99

/**
 * App-private files that keep a take on disk until it reaches shared storage, so a process that dies
 * before saving loses nothing. Files are `<name>@<rate>.pcm`: raw little-endian mono PCM16.
 */
class RecordingDrafts(private val directory: File) {
    /** Creates a new draft without overwriting one, adding `-2`, `-3`… to [baseName]; null if it cannot. */
    fun create(baseName: String, sampleRate: Int): RecordingDraft? {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        for (attempt in 1..MAX_NAME_SUFFIX) {
            val name = if (attempt == 1) baseName else "$baseName-$attempt"
            val file = File(directory, "$name@$sampleRate.pcm")
            // Claim before creating, so a concurrent recovery never takes the new file.
            if (!claim(file)) continue
            val created = try {
                file.createNewFile()
            } catch (_: IOException) {
                release(file)
                return null
            }
            if (created) return RecordingDraft(file, "$name.wav", sampleRate)
            release(file)
        }
        return null
    }

    /** Claims every well-named draft that nobody in this process owns, in name order. */
    fun claimLeftovers(): List<RecordingDraft> =
        directory.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { file ->
            val match = DRAFT_FILE_NAME.matchEntire(file.name) ?: return@mapNotNull null
            val rate = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..PCM_MAX_SAMPLE_RATE }
            if (rate == null || !file.isFile || !claim(file)) return@mapNotNull null
            RecordingDraft(file, "${match.groupValues[1]}.wav", rate)
        }

    internal companion object {
        // One owner per draft across every controller in the process (design 4.6).
        private val claimed = HashSet<String>()

        fun claim(file: File): Boolean = synchronized(claimed) { claimed.add(file.absolutePath) }

        fun release(file: File) {
            synchronized(claimed) { claimed.remove(file.absolutePath) }
        }
    }
}

/** One draft owned by its creator or by a recovery until [release]. */
class RecordingDraft internal constructor(private val file: File, val displayName: String, val sampleRate: Int) {
    private var output: FileOutputStream? = null
    private var closed = false

    /** False once any append failed; the take then lives only in memory. */
    var isIntact = true
        private set

    /** Writes straight to the file (no buffer), so written chunks survive the process. */
    fun append(samples: ShortArray, offset: Int, count: Int): Boolean {
        if (!isIntact) return false
        if (closed) {
            isIntact = false
            return false
        }
        return try {
            val stream = output ?: FileOutputStream(file, true).also { output = it }
            val bytes = ByteBuffer.allocate(count * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(samples, offset, count)
            stream.write(bytes.array())
            true
        } catch (_: IOException) {
            isIntact = false
            false
        }
    }

    fun close() {
        closed = true
        try {
            output?.close()
        } catch (_: IOException) {
        }
        output = null
    }

    /** Null when no complete sample was written; a trailing half sample is dropped. */
    fun loadClip(): PcmClip? {
        val bytes = file.readBytes()
        val samples = ShortArray(bytes.size / Short.SIZE_BYTES)
        if (samples.isEmpty()) return null
        ByteBuffer.wrap(bytes, 0, samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(samples)
        return PcmClip(samples, sampleRate)
    }

    fun delete() {
        close()
        file.delete()
    }

    fun release() = RecordingDrafts.release(file)
}

/**
 * Saves every unowned draft as WAV through [save] and deletes the ones that were saved. A failed save
 * keeps its draft for the next recovery. Returns how many drafts were saved.
 */
suspend fun saveLeftoverDrafts(drafts: RecordingDrafts, save: suspend (displayName: String, wav: ByteArray) -> Unit): Int {
    var saved = 0
    for (draft in drafts.claimLeftovers()) {
        try {
            val clip = draft.loadClip()
            if (clip == null) {
                draft.delete()
            } else {
                save(draft.displayName, encodeWavPcm16(clip))
                draft.delete()
                saved++
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Kept on disk; the next recovery tries again.
        } finally {
            draft.release()
        }
    }
    return saved
}
