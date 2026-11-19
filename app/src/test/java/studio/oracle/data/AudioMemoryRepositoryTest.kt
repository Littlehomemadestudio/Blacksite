package studio.oracle.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.oracle.data.local.AudioMemoryEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.security.LocalAudioFileCipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioMemoryRepositoryTest {
    private val key = SecretKeySpec(ByteArray(32) { (it * 11).toByte() }, "AES")

    @Test
    fun newRecordingIsEncryptedBeforeItIsPersistedAndCanBeMaterialized() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, OracleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = AudioMemoryRepository(
            context,
            database.audioMemoryDao(),
            "workspace-a",
            LocalAudioFileCipher { key }
        )
        try {
            val plaintext = ByteArray(2_048) { (it % 251).toByte() }
            val capture = repository.newRecordingFile().apply { writeBytes(plaintext) }
            val record = repository.saveRecording("Encrypted meeting", capture, 12_000L)

            assertFalse(capture.exists())
            assertTrue(record.storagePath.endsWith(".wav.enc"))
            val stored = File(context.filesDir, record.storagePath)
            assertTrue(stored.isFile)
            assertFalse(stored.readBytes().contentEquals(plaintext))

            val materialized = repository.materializeForRead(record)
            assertTrue(materialized != null)
            assertArrayEquals(plaintext, materialized!!.readBytes())
            assertTrue(materialized.canonicalPath.startsWith(context.cacheDir.canonicalPath))
            assertTrue(materialized.delete())

            val deleted = repository.delete(record.id)
            assertTrue(deleted.recordRemoved)
            assertTrue(deleted.fileRemoved)
            assertFalse(stored.exists())
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyPlaintextRecordingsAreMigratedAndRemovedFromPersistentStorage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, OracleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = AudioMemoryRepository(
            context,
            database.audioMemoryDao(),
            "workspace-a",
            LocalAudioFileCipher { key }
        )
        try {
            val id = UUID.randomUUID().toString()
            val plaintext = ByteArray(4_096) { ((it * 13) % 253).toByte() }
            val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }
            val legacy = File(audioDirectory, "$id.wav").apply { writeBytes(plaintext) }
            database.audioMemoryDao().insert(
                AudioMemoryEntity(
                    id = id,
                    workspaceId = "workspace-a",
                    title = "Legacy recording",
                    storagePath = "audio/$id.wav",
                    mimeType = "audio/wav",
                    sizeBytes = plaintext.size.toLong(),
                    durationMs = 8_000L,
                    createdAt = 100L,
                    transcript = "",
                    summary = "",
                    tasksJson = "[]",
                    decisionsJson = "[]"
                )
            )

            val result = repository.migrateLegacyRecordings()
            assertEquals(1, result.migrated)
            assertEquals(0, result.failures)
            assertFalse(legacy.exists())

            val migrated = database.audioMemoryDao().findRecord(id, "workspace-a")
            assertTrue(migrated!!.storagePath.endsWith(".wav.enc"))
            val materialized = repository.materializeForRead(migrated)
            assertTrue(materialized != null)
            assertArrayEquals(plaintext, materialized!!.readBytes())
            assertTrue(materialized.delete())
            repository.delete(id)
        } finally {
            database.close()
        }
    }

    @Test
    fun anotherWorkspaceCannotMaterializeOrMigrateItsRecording() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, OracleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = AudioMemoryRepository(
            context,
            database.audioMemoryDao(),
            "workspace-a",
            LocalAudioFileCipher { key }
        )
        var legacy: File? = null
        try {
            val id = UUID.randomUUID().toString()
            val plaintext = ByteArray(512) { it.toByte() }
            val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }
            legacy = File(audioDirectory, "$id.wav").apply { writeBytes(plaintext) }
            database.audioMemoryDao().insert(
                AudioMemoryEntity(
                    id = id,
                    workspaceId = "workspace-b",
                    title = "Other workspace",
                    storagePath = "audio/$id.wav",
                    mimeType = "audio/wav",
                    sizeBytes = plaintext.size.toLong(),
                    durationMs = 1_000L,
                    createdAt = 100L,
                    transcript = "",
                    summary = "",
                    tasksJson = "[]",
                    decisionsJson = "[]"
                )
            )

            assertNull(repository.materializeForRead(database.audioMemoryDao().findRecord(id, "workspace-b")!!))
            val migration = repository.migrateLegacyRecordings()
            assertEquals(0, migration.migrated)
            assertTrue(legacy.exists())
        } finally {
            legacy?.delete()
            database.close()
        }
    }
}
