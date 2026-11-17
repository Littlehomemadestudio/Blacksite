package studio.oracle.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.oracle.data.local.AudioMemoryEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.data.local.WorkflowEntity
import studio.oracle.data.local.WorkflowStepEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalWorkflowRunnerAudioTest {
    private lateinit var database: OracleDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OracleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun audioSearchWorkflowReadsOnlyTheActiveWorkspaceAndPersistsTheMatchLog() = runBlocking {
        val workspaceId = "workspace-a"
        val workflow = WorkflowEntity(
            id = "workflow-a",
            title = "Find meeting decisions",
            description = "",
            createdAt = 1L,
            updatedAt = 1L,
            workspaceId = workspaceId
        )
        database.workflowDao().insertWorkflow(workflow)
        database.audioMemoryDao().insert(
            AudioMemoryEntity(
                id = "audio-a",
                workspaceId = workspaceId,
                title = "Algebra planning meeting",
                storagePath = "audio/audio-a.wav",
                mimeType = "audio/wav",
                sizeBytes = 512L,
                durationMs = 60_000L,
                createdAt = 2L,
                transcript = "We decided to review algebra chapter three on Thursday.",
                summary = "Review algebra chapter three.",
                tasksJson = """["Prepare algebra exercises"]""",
                decisionsJson = """["Review chapter three"]"""
            )
        )
        database.audioMemoryDao().insert(
            AudioMemoryEntity(
                id = "audio-b",
                workspaceId = "workspace-b",
                title = "Private algebra meeting",
                storagePath = "audio/audio-b.wav",
                mimeType = "audio/wav",
                sizeBytes = 512L,
                durationMs = 60_000L,
                createdAt = 3L,
                transcript = "This belongs to another workspace.",
                summary = "",
                tasksJson = "[]",
                decisionsJson = "[]"
            )
        )
        val step = WorkflowStepEntity(
            id = "step-a",
            workflowId = workflow.id,
            position = 0,
            actionType = WorkflowAction.SEARCH_AUDIO.key,
            title = "algebra",
            input = ""
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val documents = DocumentRepository(context, database.documentDao(), workspaceId)
        val runner = LocalWorkflowRunner(
            database.workflowDao(),
            database.audioMemoryDao(),
            documents,
            workspaceId
        )

        val result = runner.execute(workflow, listOf(step))
        val logs = database.workflowDao().logsForRun(result.runId, workspaceId)
        val detail = logs.joinToString("\n") { it.detail }

        assertEquals("SUCCEEDED", result.status)
        assertTrue(detail.contains("Algebra planning meeting"))
        assertTrue(detail.contains("Review algebra chapter three"))
        assertTrue(detail.contains("1 local audio memories"))
        assertTrue(!detail.contains("Private algebra meeting"))
        assertTrue(!detail.contains("another workspace"))
    }
    @Test
    fun scheduledRunIsRecordedAsAnEnabledLocalTrigger() = runBlocking {
        val workspaceId = "workspace-scheduled"
        val workflow = WorkflowEntity(
            id = "workflow-scheduled",
            title = "Scheduled note",
            description = "",
            createdAt = 1L,
            updatedAt = 1L,
            workspaceId = workspaceId
        )
        database.workflowDao().insertWorkflow(workflow)
        val step = WorkflowStepEntity(
            id = "step-scheduled",
            workflowId = workflow.id,
            position = 0,
            actionType = WorkflowAction.CREATE_NOTE.key,
            title = "Daily review",
            input = "Review today's priorities."
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runner = LocalWorkflowRunner(
            database.workflowDao(),
            database.audioMemoryDao(),
            DocumentRepository(context, database.documentDao(), workspaceId),
            workspaceId
        )

        val result = runner.execute(workflow, listOf(step), origin = WorkflowRunOrigin.SCHEDULED)
        val logs = database.workflowDao().logsForRun(result.runId, workspaceId)

        assertEquals("SUCCEEDED", result.status)
        assertTrue(logs.first().detail.contains("Scheduled trigger"))
        assertEquals(1, database.workflowDao().observeNotes(workspaceId).first().size)
    }
}
