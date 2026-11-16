package studio.oracle.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import studio.oracle.data.local.AudioMemoryDao
import studio.oracle.data.local.AudioMemoryEntity
import java.io.File
import java.io.IOException
import java.util.UUID

data class AudioDeleteResult(
    val recordRemoved: Boolean,
    val fileRemoved: Boolean
)

/** Workspace-scoped local persistence for recordings and their derived knowledge. */
class AudioMemoryRepository(
    context: Context,
    private val dao: AudioMemoryDao,
    private val workspaceId: String
) {
    private val appContext = context.applicationContext
    private val audioDirectory = File(appContext.filesDir, "audio")

    val records: Flow<List<AudioMemoryEntity>> = dao.observeRecords(workspaceId)

    fun newRecordingFile(): File {
        if (!audioDirectory.exists() && !audioDirectory.mkdirs()) {
            throw IOException("Could not create the private audio archive.")
        }
        return File(audioDirectory, UUID.randomUUID().toString() + ".wav")
    }

    suspend fun saveRecording(title: String, file: File, durationMs: Long): AudioMemoryEntity =
        withContext(Dispatchers.IO) {
            require(durationMs in 1L..WavAudioRecorder.MAX_DURATION_MS) {
                "Recording duration is outside the supported range."
            }
            val canonicalDirectory = audioDirectory.canonicalFile
            val canonicalFile = file.canonicalFile
            require(canonicalFile.parentFile == canonicalDirectory) {
                "Recording must be stored inside ORACLE's private audio directory."
            }
            if (!canonicalFile.isFile || canonicalFile.length() <= 44L) {
                throw IOException("The recording is empty or incomplete.")
            }
            if (canonicalFile.length() > MAX_FILE_BYTES) {
                canonicalFile.delete()
                throw IOException("The recording exceeds the 30 MB audio archive limit.")
            }
            val id = canonicalFile.nameWithoutExtension
            require(id.matches(Regex("[A-Za-z0-9-]{20,40}"))) {
                "Recording identifier is invalid."
            }
            val record = AudioMemoryEntity(
                id = id,
                workspaceId = workspaceId,
                title = title.trim().take(80).ifBlank { "Voice note" },
                storagePath = "audio/" + canonicalFile.name,
                mimeType = "audio/wav",
                sizeBytes = canonicalFile.length(),
                durationMs = durationMs,
                createdAt = System.currentTimeMillis(),
                transcript = "",
                summary = "",
                tasksJson = "[]",
                decisionsJson = "[]"
            )
            dao.insert(record)
            record
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

    fun fileFor(record: AudioMemoryEntity): File? {
        if (record.workspaceId != workspaceId) return null
        return runCatching {
            val file = File(appContext.filesDir, record.storagePath).canonicalFile
            if (file.parentFile == audioDirectory.canonicalFile && file.isFile) file else null
        }.getOrNull()
    }

    suspend fun delete(recordId: String): AudioDeleteResult = withContext(Dispatchers.IO) {
        val record = dao.findRecord(recordId, workspaceId)
            ?: return@withContext AudioDeleteResult(false, false)
        val removed = dao.delete(recordId, workspaceId) == 1
        if (!removed) return@withContext AudioDeleteResult(false, false)
        val file = fileFor(record)
        val fileRemoved = file == null || !file.exists() || file.delete()
        AudioDeleteResult(true, fileRemoved)
    }

    companion object {
        const val MAX_FILE_BYTES = 30L * 1024L * 1024L

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
