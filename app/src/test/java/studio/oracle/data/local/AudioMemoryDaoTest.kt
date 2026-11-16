package studio.oracle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioMemoryDaoTest {
    @Test
    fun observationsAndSearchStayInsideTheRequestedWorkspace() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, OracleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.audioMemoryDao()
            dao.insert(record("one", "workspace-a", "Project sync", "Discussed the launch schedule."))
            dao.insert(record("two", "workspace-b", "Private note", "Discussed a different project."))

            val workspaceA = dao.observeRecords("workspace-a").first()
            assertEquals(1, workspaceA.size)
            assertEquals("one", workspaceA.single().id)
            assertEquals(1, dao.search("workspace-a", "launch").size)
            assertTrue(dao.search("workspace-a", "different").isEmpty())
            assertEquals(1, dao.search("workspace-b", "different").size)
        } finally {
            database.close()
        }
    }

    private fun record(id: String, workspace: String, title: String, transcript: String) =
        AudioMemoryEntity(
            id = id,
            workspaceId = workspace,
            title = title,
            storagePath = "audio/$id.wav",
            mimeType = "audio/wav",
            sizeBytes = 1_024,
            durationMs = 10_000,
            createdAt = 100,
            transcript = transcript,
            summary = "",
            tasksJson = "[]",
            decisionsJson = "[]"
        )
}
