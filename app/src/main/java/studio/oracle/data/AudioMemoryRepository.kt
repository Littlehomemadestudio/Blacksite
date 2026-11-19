package studio.oracle.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import studio.oracle.data.local.AudioMemoryDao
import studio.oracle.data.local.AudioMemoryEntity
import studio.oracle.security.LocalAudioFileCipher
import java.io.File
import java.io.IOException
import java.util.UUID

data class AudioDeleteResult(
    val recordRemoved: Boolean,
    val fileRemoved: Boolean
)

data class AudioStorageMigrationResult(
    val migrated: Int,
    val failures: Int
)

/**
 * Workspace-scoped local persistence for recordings and their derived knowledge.
 *
 * Audio payloads are AES-GCM encrypted before entering persistent app files. Plain WAV
 * materializations exist only in a dedicated cache directory while recording, playback,
 * or an explicitly approved transcription is active.
 */
class AudioMemoryRepository(
    context: Context,
    private val dao: AudioMemoryDao,
    private val workspaceId: String,
    private val cipher: LocalAudioFileCipher = LocalAudioFileCipher()
) {
    private val appContext = context.applicationContext
    private val audioDirectory = File(appContext.filesDir, "audio")
    private val pendingDirectory = File(appContext.cacheDir, "oracle-audio-pending")
    private val decryptedDirectory = File(appContext.cacheDir, "oracle-audio-decrypted")

    val records: Flow<List<AudioMemoryEntity>> = dao.observeRecords(workspaceId)

    /** Creates a disposable capture target; it is not placed in persistent filesDir. */
    fun newRecordingFile(): File {
        ensureDirectory(pendingDirectory, "temporary audio capture")
        val file = File(pendingDirectory, UUID.randomUUID().toString() + ".wav")
        if (!file.createNewFile()) throw IOException("Could not reserve a temporary audio recording.")
        return file
    }

    suspend fun saveRecording(title: String, file: File, durationMs: Long): AudioMemoryEntity =
        withContext(Dispatchers.IO) {
            require(durationMs in 1L..WavAudioRecorder.MAX_DURATION_MS) {
                "Recording duration is outside the supported range."
            }
            val canonicalPending = pendingDirectory.canonicalFile
            val canonicalFile = file.canonicalFile
            require(canonicalFile.parentFile == canonicalPending) {
                "Recording must be finalized from ORACLE's temporary audio capture directory."
            }
            if (!canonicalFile.isFile || canonicalFile.length() <= 44L) {
                throw IOException("The recording is empty or incomplete.")
            }
            if (canonicalFile.length() > MAX_FILE_BYTES) {
                canonicalFile.delete()
                throw IOException("The recording exceeds the 30 MB audio archive limit.")
            }

            val id = canonicalFile.nameWithoutExtension
            require(id.matches(RECORD_ID_PATTERN)) { "Recording identifier is invalid." }
            ensureDirectory(audioDirectory, "private audio archive")
            val encryptedFile = encryptedFileFor(id)
            if (encryptedFile.exists() && !encryptedFile.delete()) {
                throw IOException("Could not replace an incomplete encrypted audio file.")
            }

            cipher.encrypt(canonicalFile, encryptedFile, id)
            if (!canonicalFile.delete()) {
                encryptedFile.delete()
                throw IOException("The encrypted recording was created, but its temporary plaintext copy could not be removed.")
            }

            val record = AudioMemoryEntity(
                id = id,
                workspaceId = workspaceId,
                title = title.trim().take(80).ifBlank { "Voice note" },
                storagePath = "audio/" + encryptedFile.name,
                mimeType = "audio/wav",
                sizeBytes = encryptedFile.length(),
                durationMs = durationMs,
                createdAt = System.currentTimeMillis(),
                transcript = "",
                summary = "",
                tasksJson = "[]",
                decisionsJson = "[]"
            )
            try {
                dao.insert(record)
            } catch (failure: Exception) {
                runCatching { cipher.decrypt(encryptedFile, canonicalFile, id) }
                encryptedFile.delete()
                throw failure
            }
            record
        }

    /**
     * Encrypts old audio/<uuid>.wav rows in the active workspace. Also clears abandoned
     * plaintext capture/decryption cache files left by a process termination.
     */
    suspend fun migrateLegacyRecordings(): AudioStorageMigrationResult = withContext(Dispatchers.IO) {
        var failures = clearTemporaryFiles(pendingDirectory)
        failures += clearTemporaryFiles(decryptedDirectory)
        var migrated = 0
        val snapshot = dao.recordsSnapshot(workspaceId)
        for (record in snapshot) {
            val wasLegacy = !record.storagePath.endsWith(ENCRYPTED_SUFFIX)
            try {
                ensureEncrypted(record)
                if (wasLegacy) migrated++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures++
            }
        }
        AudioStorageMigrationResult(migrated, failures)
    }

    /**
     * Returns a short-lived plaintext WAV in cacheDir for MediaPlayer or an explicitly
     * approved Gemini upload. Callers MUST delete the returned file in a finally/on-stop path.
     */
    suspend fun materializeForRead(record: AudioMemoryEntity): File? = withContext(Dispatchers.IO) {
        if (record.workspaceId != workspaceId) return@withContext null
        val securedRecord = ensureEncrypted(record)
        val encrypted = storedFileFor(securedRecord)
            ?: throw IOException("The encrypted audio file is missing from this workspace.")
        if (!encrypted.name.endsWith(ENCRYPTED_SUFFIX)) {
            throw IOException("The audio archive has not been encrypted successfully.")
        }
        ensureDirectory(decryptedDirectory, "temporary audio playback")
        val temporary = File(decryptedDirectory, record.id + "-" + UUID.randomUUID() + ".wav")
        try {
            cipher.decrypt(encrypted, temporary, record.id)
            temporary
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    suspend fun saveAnalysis(recordId: String, analysis: AudioAnalysis): Boolean =
        withContext(Dispatchers.IO) {
            dao.saveAnalysis(
                id = recordId,
                workspaceId = workspaceId,
                transcript = analysis.transcript,
                summary = analysis.summary,
                tasksJson = encodeList(analysis.tasks),
                decisionsJson = encodeList(analysis.decisions)
            ) == 1
        }

    suspend fun search(query: String): List<AudioMemoryEntity> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(100)
        if (clean.isBlank()) emptyList() else dao.search(workspaceId, clean)
    }

    suspend fun delete(recordId: String): AudioDeleteResult = withContext(Dispatchers.IO) {
        val record = dao.findRecord(recordId, workspaceId)
            ?: return@withContext AudioDeleteResult(false, false)
        val removed = dao.delete(recordId, workspaceId) == 1
        if (!removed) return@withContext AudioDeleteResult(false, false)

        val stored = storedFileFor(record)
        val legacy = File(audioDirectory, record.id + ".wav")
        val targets = listOfNotNull(stored, legacy).distinctBy { it.absolutePath }
        val fileRemoved = targets.all { !it.exists() || it.delete() }
        AudioDeleteResult(true, fileRemoved)
    }

    private suspend fun ensureEncrypted(record: AudioMemoryEntity): AudioMemoryEntity {
        require(record.workspaceId == workspaceId) {
            "This recording does not belong to the active workspace."
        }
        ensureDirectory(audioDirectory, "private audio archive")
        if (record.storagePath.endsWith(ENCRYPTED_SUFFIX)) {
            val legacyCopy = File(audioDirectory, record.id + ".wav")
            if (legacyCopy.exists() && !legacyCopy.delete()) {
                throw IOException("An older unencrypted copy could not be removed. Retry securing this recording.")
            }
            val encrypted = storedFileFor(record)
                ?: throw IOException("The encrypted audio file is missing from this workspace.")
            return record.copy(sizeBytes = encrypted.length())
        }

        val source = storedFileFor(record)
            ?: throw IOException("The legacy audio file is missing from this workspace.")
        val target = encryptedFileFor(record.id)
        if (target.exists() && !target.delete()) {
            throw IOException("Could not replace an incomplete encrypted audio file.")
        }
        cipher.encrypt(source, target, record.id)

        val updatedPath = "audio/" + target.name
        val updated = dao.updateStoragePath(record.id, workspaceId, updatedPath, target.length())
        if (updated != 1) {
            target.delete()
            throw IOException("The recording changed while it was being secured. Retry from the archive.")
        }
        if (!source.delete()) {
            throw IOException("The encrypted copy is saved, but the older plaintext WAV could not be removed. Retry securing this recording.")
        }
        return record.copy(storagePath = updatedPath, sizeBytes = target.length())
    }

    private fun storedFileFor(record: AudioMemoryEntity): File? {
        if (record.workspaceId != workspaceId) return null
        return runCatching {
            val file = File(appContext.filesDir, record.storagePath).canonicalFile
            if (file.parentFile == audioDirectory.canonicalFile && file.isFile) file else null
        }.getOrNull()
    }

    private fun encryptedFileFor(id: String): File {
        require(id.matches(RECORD_ID_PATTERN)) { "Recording identifier is invalid." }
        return File(audioDirectory, id + ".wav" + ENCRYPTED_SUFFIX)
    }

    private fun ensureDirectory(directory: File, label: String) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the $label directory.")
        }
        if (!directory.isDirectory) throw IOException("The $label path is not a directory.")
    }

    private fun clearTemporaryFiles(directory: File): Int {
        if (!directory.exists()) return 0
        val files = directory.listFiles() ?: return 1
        return files.count { it.isFile && !it.delete() && it.exists() }
    }

    companion object {
        const val MAX_FILE_BYTES = 30L * 1024L * 1024L
        private const val ENCRYPTED_SUFFIX = ".enc"
        private val RECORD_ID_PATTERN = Regex("[A-Za-z0-9-]{20,40}")

        fun decodeList(json: String): List<String> = runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

        private fun encodeList(values: List<String>): String =
            JSONArray().apply { values.forEach { put(it.take(500)) } }.toString()
    }
}
