package studio.oracle.data

import kotlinx.coroutines.flow.Flow
import studio.oracle.data.local.LocalNoteEntity
import studio.oracle.data.local.WorkflowDao
import studio.oracle.data.local.WorkflowEntity
import studio.oracle.data.local.WorkflowRunEntity
import studio.oracle.data.local.WorkflowStepEntity
import java.util.UUID

class WorkflowRepository(private val dao: WorkflowDao, private val workspaceId: String) {
    val workflows: Flow<List<WorkflowEntity>> = dao.observeWorkflows(workspaceId)
    val notes: Flow<List<LocalNoteEntity>> = dao.observeNotes(workspaceId)
    val runs: Flow<List<WorkflowRunEntity>> = dao.observeRuns(workspaceId)

    suspend fun create(): WorkflowEntity {
        val now = System.currentTimeMillis()
        val workflow = WorkflowEntity(
            id = UUID.randomUUID().toString(),
            title = "Untitled workflow",
            description = "",
            createdAt = now,
            updatedAt = now,
            workspaceId = workspaceId
        )
        dao.insertWorkflow(workflow)
        return workflow
    }

    suspend fun stepsFor(workflowId: String): List<WorkflowStepEntity> =
        dao.stepsFor(workflowId, workspaceId)

    suspend fun save(workflow: WorkflowEntity, steps: List<WorkflowStepEntity>) {
        require(workflow.workspaceId == workspaceId) { "Workflow does not belong to this workspace." }
        val normalizedSteps = steps.sortedBy { it.position }.mapIndexed { index, step ->
            step.copy(workflowId = workflow.id, position = index)
        }
        val saved = dao.saveWorkflowAndSteps(
            id = workflow.id,
            workspaceId = workspaceId,
            title = workflow.title.trim().take(WorkflowRules.MAX_TITLE_CHARS).ifBlank { "Untitled workflow" },
            description = workflow.description.trim().take(500),
            updatedAt = System.currentTimeMillis(),
            steps = normalizedSteps
        )
        check(saved) { "Workflow no longer exists in this workspace." }
    }

    suspend fun delete(workflowId: String) = dao.deleteWorkflow(workflowId, workspaceId)
}
