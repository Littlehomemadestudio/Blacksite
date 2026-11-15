package studio.oracle.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.oracle.data.local.LocalNoteEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.data.local.WorkflowEntity
import studio.oracle.data.local.WorkflowRunEntity
import studio.oracle.data.local.WorkflowStepEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowWorkspaceIsolationTest {
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
    fun workflowsStepsAndRunHistoryAreScopedToTheirWorkspace() = runBlocking {
        val dao = database.workflowDao()
        dao.insertWorkflow(workflow("workflow-a", "workspace-a"))
        dao.insertWorkflow(workflow("workflow-b", "workspace-b"))
        dao.insertSteps(
            listOf(
                step("step-a", "workflow-a"),
                step("step-b", "workflow-b")
            )
        )
        dao.insertRun(run("run-a", "workflow-a"))
        dao.insertRun(run("run-b", "workflow-b"))

        assertEquals(
            listOf("workflow-a"),
            dao.observeWorkflows("workspace-a").first().map { it.id }
        )
        assertEquals(
            listOf("step-a"),
            dao.stepsFor("workflow-a", "workspace-a").map { it.id }
        )
        assertTrue(dao.stepsFor("workflow-b", "workspace-a").isEmpty())
        assertEquals(
            listOf("run-a"),
            dao.observeRuns("workspace-a").first().map { it.id }
        )
    }

    @Test
    fun updateAndDeleteCannotTargetAnotherWorkspacesWorkflow() = runBlocking {
        val dao = database.workflowDao()
        dao.insertWorkflow(workflow("workflow-a", "workspace-a"))
        dao.insertWorkflow(workflow("workflow-b", "workspace-b"))

        val updated = dao.updateWorkflow(
            id = "workflow-b",
            workspaceId = "workspace-a",
            title = "Hijacked",
            description = "",
            updatedAt = 5L
        )
        val deleted = dao.deleteWorkflow("workflow-b", "workspace-a")

        assertEquals(0, updated)
        assertEquals(0, deleted)
        assertEquals("workflow-b", dao.findWorkflow("workflow-b", "workspace-b")?.title)
    }

    @Test
    fun noteTitlesAreUniqueOnlyInsideTheirWorkspace() = runBlocking {
        val dao = database.workflowDao()
        dao.insertNote(note("note-a", "workspace-a", "Physics plan"))
        dao.insertNote(note("note-b", "workspace-b", "Physics plan"))

        assertEquals("A", dao.findNoteByTitle("Physics plan", "workspace-a")?.content)
        assertEquals("B", dao.findNoteByTitle("Physics plan", "workspace-b")?.content)
        assertNull(dao.findNoteByTitle("Missing", "workspace-a"))
        assertEquals(
            listOf("note-a"),
            dao.observeNotes("workspace-a").first().map { it.id }
        )
    }

    @Test
    fun noteMutationCannotCrossWorkspaceBoundary() = runBlocking {
        val dao = database.workflowDao()
        dao.insertNote(note("note-a", "workspace-a", "Shared title"))
        dao.insertNote(note("note-b", "workspace-b", "Shared title"))

        val changed = dao.appendToNote(
            id = "note-b",
            workspaceId = "workspace-a",
            suffix = "unauthorized append",
            now = 10L
        )

        assertEquals(0, changed)
        assertEquals("B", dao.findNoteByTitle("Shared title", "workspace-b")?.content)
    }

    private fun workflow(id: String, workspaceId: String) = WorkflowEntity(
        id = id,
        title = id,
        description = "",
        createdAt = 1L,
        updatedAt = 1L,
        workspaceId = workspaceId
    )

    private fun step(id: String, workflowId: String) = WorkflowStepEntity(
        id = id,
        workflowId = workflowId,
        position = 0,
        actionType = WorkflowAction.CREATE_NOTE.key,
        title = "Note",
        input = "Body"
    )

    private fun note(id: String, workspaceId: String, title: String) = LocalNoteEntity(
        id = id,
        title = title,
        content = if (workspaceId == "workspace-a") "A" else "B",
        createdAt = 1L,
        updatedAt = 1L,
        workspaceId = workspaceId
    )

    private fun run(id: String, workflowId: String) = WorkflowRunEntity(
        id = id,
        workflowId = workflowId,
        status = "SUCCEEDED",
        startedAt = 1L,
        finishedAt = 2L,
        summary = "done"
    )
}
