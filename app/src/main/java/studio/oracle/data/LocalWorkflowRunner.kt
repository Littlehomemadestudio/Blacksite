package studio.oracle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import studio.oracle.data.local.LocalNoteEntity
import studio.oracle.data.local.AudioMemoryDao
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

enum class WorkflowRunOrigin(
    val initialSummary: String,
    val startLog: String
) {
    MANUAL(
        "Started after explicit user confirmation.",
        "Manual run started after explicit confirmation. All actions execute locally; no Gemini request is made."
    ),
    SCHEDULED(
        "Started by an enabled local recurring trigger.",
        "Scheduled trigger started this run. The saved workflow executes locally; no Gemini request or network access is used."
    )
}

/** Executes only the explicit local actions in WorkflowAction. */
class LocalWorkflowRunner(
    private val dao: WorkflowDao,
    private val audioMemories: AudioMemoryDao,
    private val documents: DocumentRepository,
    private val workspaceId: String
) {
    suspend fun execute(
        workflow: WorkflowEntity,
        steps: List<WorkflowStepEntity>,
        origin: WorkflowRunOrigin = WorkflowRunOrigin.MANUAL
    ): WorkflowRunResult {
        if (workflow.workspaceId != workspaceId || dao.findWorkflow(workflow.id, workspaceId) == null) {
            return WorkflowRunResult("", "INVALID", "This workflow does not belong to the active workspace.")
        }
        val validation = WorkflowRules.validate(steps, expectedWorkflowId = workflow.id)
        if (validation != null) {
            if (origin == WorkflowRunOrigin.SCHEDULED) {
                val invalidRunId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                dao.insertRun(
                    WorkflowRunEntity(
                        id = invalidRunId,
                        workflowId = workflow.id,
                        status = "INVALID",
                        startedAt = now,
                        finishedAt = now,
                        summary = validation
                    )
                )
                log(invalidRunId, null, "FAILED", "Scheduled trigger skipped this run: $validation")
                return WorkflowRunResult(invalidRunId, "INVALID", validation)
            }
            return WorkflowRunResult("", "INVALID", validation)
        }

        val ordered = steps.sortedBy { it.position }
        val runId = UUID.randomUUID().toString()
        var runCreated = true
        var completed = 0
        try {
            dao.insertRun(
                WorkflowRunEntity(
                    id = runId,
                    workflowId = workflow.id,
                    status = "RUNNING",
                    startedAt = System.currentTimeMillis(),
                    finishedAt = null,
                    summary = origin.initialSummary
                )
            )
            runCreated = true
            log(runId, null, "INFO", origin.startLog)

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
                                    updatedAt = now,
                                    workspaceId = workspaceId
                                )
                            )
                            "Created local note “${step.title.trim()}” (${step.input.length} characters)."
                        }
                        WorkflowAction.APPEND_NOTE -> {
                            val note = dao.findNoteByTitle(step.title.trim(), workspaceId)
                                ?: error("No local note titled “${step.title.trim()}” exists in this workspace. Create it first or correct the title.")
                            val changed = dao.appendToNote(note.id, workspaceId, step.input, System.currentTimeMillis())
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
                        WorkflowAction.SEARCH_AUDIO -> {
                            val hits = audioMemories.search(workspaceId, step.title.trim()).take(5)
                            if (hits.isEmpty()) {
                                "No matching local audio memories found for “${step.title.trim()}”. No audio or transcript was uploaded."
                            } else {
                                val matches = hits.joinToString("\n") { record ->
                                    val excerpt = record.summary.ifBlank { record.transcript }
                                        .replace(Regex("\\s+"), " ")
                                        .take(220)
                                    record.title + if (excerpt.isBlank()) "" else " — " + excerpt
                                }
                                "Found ${hits.size} local audio memories for “${step.title.trim()}” (workspace-isolated, offline):\n$matches"
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
            log(runId, null, "INFO", "Run completed successfully.")
            dao.finishRun(runId, "SUCCEEDED", System.currentTimeMillis(), summary)
            return WorkflowRunResult(runId, "SUCCEEDED", summary)
        } catch (cancelled: CancellationException) {
            if (runCreated) {
                withContext(NonCancellable) {
                    val summary = "Run cancelled after $completed of ${ordered.size} steps."
                    runCatching { log(runId, null, "CANCELLED", summary) }
                    runCatching {
                        dao.finishRun(runId, "CANCELLED", System.currentTimeMillis(), summary)
                    }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            val detail = failure.message?.take(600) ?: "Unexpected local execution failure."
            val summary = "Run failed after $completed of ${ordered.size} steps. $detail"
            if (runCreated) {
                withContext(NonCancellable) {
                    runCatching { log(runId, null, "FAILED", summary) }
                    runCatching {
                        dao.finishRun(runId, "FAILED", System.currentTimeMillis(), summary)
                    }
                }
            }
            return WorkflowRunResult(runId, "FAILED", summary)
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
