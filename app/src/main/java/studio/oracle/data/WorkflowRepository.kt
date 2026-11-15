package studio.oracle.data

import kotlinx.coroutines.flow.Flow
import studio.oracle.data.local.LocalNoteEntity
import studio.oracle.data.local.WorkflowDao
import studio.oracle.data.local.WorkflowEntity
import studio.oracle.data.local.WorkflowRunEntity
import studio.oracle.data.local.WorkflowStepEntity
import java.util.UUID

class WorkflowRepository(private val dao: WorkflowDao) {
    val workflows: Flow<List<WorkflowEntity>> = dao.observeWorkflows()
    val notes: Flow<List<LocalNoteEntity>> = dao.observeNotes()
    val runs: Flow<List<WorkflowRunEntity>> = dao.observeRuns()

    suspend fun create(): WorkflowEntity {
        val now = System.currentTimeMillis()
        val workflow = WorkflowEntity(
            id = UUID.randomUUID().toString(),
            title = "Untitled workflow",
            description = "",
            createdAt = now,
            updatedAt = now
        )
        dao.insertWorkflow(workflow)
        return workflow
    }

    suspend fun stepsFor(workflowId: String): List<WorkflowStepEntity> = dao.stepsFor(workflowId)

    suspend fun save(workflow: WorkflowEntity, steps: List<WorkflowStepEntity>) {
        val now = System.currentTimeMillis()
        dao.updateWorkflow(
            id = workflow.id,
            title = workflow.title.trim().take(WorkflowRules.MAX_TITLE_CHARS).ifBlank { "Untitled workflow" },
            description = workflow.description.trim().take(500),
            updatedAt = now
        )
        dao.replaceSteps(
            workflow.id,
            steps.sortedBy { it.position }.mapIndexed { index, step ->
                step.copy(workflowId = workflow.id, position = index)
            }
        )
    }

    suspend fun delete(workflowId: String) = dao.deleteWorkflow(workflowId)
}
