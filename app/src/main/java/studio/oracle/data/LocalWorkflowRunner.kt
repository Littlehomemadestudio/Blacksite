package studio.oracle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import studio.oracle.data.local.LocalNoteEntity
import studio.oracle.data.local.WorkflowDao
import studio.oracle.data.local.WorkflowEntity
import studio.oracle.data.local.WorkflowRunEntity
import studio.oracle.data.local.WorkflowRunLogEntity
import studio.oracle.data.local.WorkflowStepEntity
import java.util.UUID

data class WorkflowRunResult(
    val runId: String,
    val status: String,
    val summary: String
)

/** Executes only the explicit local actions in WorkflowAction. */
class LocalWorkflowRunner(
    private val dao: WorkflowDao,
    private val documents: DocumentRepository
) {
    suspend fun execute(
        workflow: WorkflowEntity,
        steps: List<WorkflowStepEntity>
    ): WorkflowRunResult {
        val validation = WorkflowRules.validate(steps)
        if (validation != null) return WorkflowRunResult("", "INVALID", validation)

        val ordered = steps.sortedBy { it.position }
        val runId = UUID.randomUUID().toString()
        dao.insertRun(
            WorkflowRunEntity(
                id = runId,
                workflowId = workflow.id,
                status = "RUNNING",
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
                summary = "Started after explicit user confirmation."
            )
        )
        log(runId, null, "INFO", "Manual run started. All actions execute locally; no Gemini request is made.")

        var completed = 0
        try {
            ordered.forEachIndexed { index, step ->
                currentCoroutineContext().ensureActive()
                val action = WorkflowAction.from(step.actionType)
                    ?: error("Unsupported action: ${step.actionType}")
                try {
                    val detail = when (action) {
                        WorkflowAction.CREATE_NOTE -> {
                            val now = System.currentTimeMillis()
                            dao.insertNote(
                                LocalNoteEntity(
                                    id = UUID.randomUUID().toString(),
                                    title = step.title.trim(),
                                    content = step.input,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                            "Created local note “${step.title.trim()}” (${step.input.length} characters)."
                        }
                        WorkflowAction.APPEND_NOTE -> {
                            val note = dao.findNoteByTitle(step.title.trim())
                                ?: error("No local note titled “${step.title.trim()}” exists. Create it first or correct the title.")
                            val changed = dao.appendToNote(note.id, step.input, System.currentTimeMillis())
                            if (changed != 1) error("The target note could not be updated.")
                            "Appended ${step.input.length} characters to “${note.title}”."
                        }
                        WorkflowAction.SEARCH_LIBRARY -> {
                            val hits = documents.search(step.title.trim(), limit = 5)
                            if (hits.isEmpty()) {
                                "No matching passages found for “${step.title.trim()}”. Nothing was sent or uploaded."
                            } else {
                                val sources = hits.joinToString("; ") { "${it.document.title} · ${it.chunk.sectionLabel}" }
                                "Found ${hits.size} local passages for “${step.title.trim()}”: $sources"
                            }
                        }
                    }
                    log(runId, step.id, "SUCCESS", detail)
                    completed++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val reason = failure.message?.take(600) ?: "Step failed."
                    log(runId, step.id, "FAILED", "Step ${index + 1} stopped the run: $reason")
                    val summary = "Stopped at step ${index + 1} of ${ordered.size}. $reason"
                    dao.finishRun(runId, "FAILED", System.currentTimeMillis(), summary)
                    return WorkflowRunResult(runId, "FAILED", summary)
                }
            }

            val summary = "$completed of ${ordered.size} steps completed."
            dao.finishRun(runId, "SUCCEEDED", System.currentTimeMillis(), summary)
            log(runId, null, "INFO", "Run completed successfully.")
            return WorkflowRunResult(runId, "SUCCEEDED", summary)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val summary = "Run cancelled after $completed of ${ordered.size} steps."
                log(runId, null, "CANCELLED", summary)
                dao.finishRun(runId, "CANCELLED", System.currentTimeMillis(), summary)
            }
            throw cancelled
        }
    }

    private suspend fun log(runId: String, stepId: String?, status: String, detail: String) {
        dao.insertRunLog(
            WorkflowRunLogEntity(
                runId = runId,
                stepId = stepId,
                status = status,
                detail = detail.take(1_500),
                createdAt = System.currentTimeMillis()
            )
        )
    }
}
