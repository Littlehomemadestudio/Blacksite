package studio.oracle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowTriggerDaoTest {
    @Test
    fun triggerObservationsAndMutationsStayInsideTheirWorkspace() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, OracleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val workflowDao = database.workflowDao()
            val triggerDao = database.workflowTriggerDao()
            workflowDao.insertWorkflow(workflow("workflow-a", "workspace-a"))
            workflowDao.insertWorkflow(workflow("workflow-b", "workspace-b"))

            triggerDao.upsertTrigger(trigger("workflow-a", "workspace-a"))
            triggerDao.upsertTrigger(trigger("workflow-b", "workspace-b"))

            assertEquals(1, triggerDao.observeTriggers("workspace-a").first().size)
            assertEquals("workflow-a", triggerDao.observeTriggers("workspace-a").first().single().workflowId)
            assertNull(triggerDao.findTrigger("workflow-a", "workspace-b"))
            assertEquals(60L, triggerDao.findTrigger("workflow-a", "workspace-a")?.intervalMinutes)

            assertEquals(1, triggerDao.deleteTrigger("workflow-a", "workspace-a"))
            assertTrue(triggerDao.observeTriggers("workspace-a").first().isEmpty())
            assertEquals(1, triggerDao.observeTriggers("workspace-b").first().size)
        } finally {
            database.close()
        }
    }

    private fun workflow(id: String, workspaceId: String) = WorkflowEntity(
        id = id,
        title = "Scheduled workflow",
        description = "",
        createdAt = 1L,
        updatedAt = 1L,
        workspaceId = workspaceId
    )

    private fun trigger(workflowId: String, workspaceId: String) = WorkflowTriggerEntity(
        workflowId = workflowId,
        workspaceId = workspaceId,
        intervalMinutes = 60L,
        enabled = true,
        updatedAt = 10L
    )
}
