package studio.oracle.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import studio.oracle.data.local.OracleDatabase
import studio.oracle.data.local.WorkflowDao
import studio.oracle.data.local.WorkflowTriggerDao
import studio.oracle.data.local.WorkflowTriggerEntity
import java.util.concurrent.TimeUnit

object WorkflowSchedulePolicy {
    const val MIN_INTERVAL_MINUTES = 15L

    val INTERVAL_OPTIONS_MINUTES = listOf(15L, 30L, 60L, 360L, 720L, 1_440L)

    fun validateInterval(intervalMinutes: Long): String? =
        if (intervalMinutes in INTERVAL_OPTIONS_MINUTES) null
        else "Choose one of the supported recurring intervals (minimum 15 minutes)."

    fun intervalLabel(intervalMinutes: Long): String = when (intervalMinutes) {
        15L -> "15 minutes"
        30L -> "30 minutes"
        60L -> "1 hour"
        360L -> "6 hours"
        720L -> "12 hours"
        1_440L -> "24 hours"
        else -> "$intervalMinutes minutes"
    }

    fun uniqueWorkName(workspaceId: String, workflowId: String): String =
        "oracle-workflow-$workspaceId-$workflowId"
}

sealed interface WorkflowScheduleResult {
    data class Success(val message: String) : WorkflowScheduleResult
    data class Failure(val message: String) : WorkflowScheduleResult
}

/**
 * Owns the user's explicit recurring-schedule opt-in. WorkManager is used only
 * for deferrable periodic work; no exact alarms, receivers or network constraints
 * are introduced by ORACLE.
 */
class WorkflowTriggerScheduler(
    context: Context,
    private val triggerDao: WorkflowTriggerDao,
    private val workflowDao: WorkflowDao,
    private val workspaceId: String
) {
    private val appContext = context.applicationContext

    val triggers: Flow<List<WorkflowTriggerEntity>> = triggerDao.observeTriggers(workspaceId)

    suspend fun enable(workflowId: String, intervalMinutes: Long): WorkflowScheduleResult {
        WorkflowSchedulePolicy.validateInterval(intervalMinutes)?.let {
            return WorkflowScheduleResult.Failure(it)
        }
        val workflow = workflowDao.findWorkflow(workflowId, workspaceId)
            ?: return WorkflowScheduleResult.Failure("This workflow is not available in the active workspace.")
        val steps = workflowDao.stepsFor(workflowId, workspaceId)
        val validation = WorkflowRules.validate(steps, expectedWorkflowId = workflow.id)
            ?: return try {
                val trigger = WorkflowTriggerEntity(
                    workflowId = workflow.id,
                    workspaceId = workspaceId,
                    intervalMinutes = intervalMinutes,
                    enabled = true,
                    updatedAt = System.currentTimeMillis()
                )
                triggerDao.upsertTrigger(trigger)

                val request = PeriodicWorkRequestBuilder<ScheduledWorkflowWorker>(
                    intervalMinutes,
                    TimeUnit.MINUTES
                )
                    .setInputData(
                        workDataOf(
                            ScheduledWorkflowWorker.KEY_WORKFLOW_ID to workflow.id,
                            ScheduledWorkflowWorker.KEY_WORKSPACE_ID to workspaceId
                        )
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(ScheduledWorkflowWorker.TAG)
                    .build()

                WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                    WorkflowSchedulePolicy.uniqueWorkName(workspaceId, workflow.id),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                WorkflowScheduleResult.Success(
                    "SCHEDULE ACTIVE · every ${WorkflowSchedulePolicy.intervalLabel(intervalMinutes)} · local only"
                )
            } catch (_: Exception) {
                runCatching { triggerDao.deleteTrigger(workflow.id, workspaceId) }
                WorkflowScheduleResult.Failure("Could not schedule this workflow. Check local storage and try again.")
            }
        return WorkflowScheduleResult.Failure(validation)
    }

    suspend fun disable(workflowId: String) {
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(
                WorkflowSchedulePolicy.uniqueWorkName(workspaceId, workflowId)
            )
        }
        // Even if cancellation cannot be enqueued, the worker checks this row and
        // exits without touching workspace data.
        triggerDao.deleteTrigger(workflowId, workspaceId)
    }
}

/** Executes only the saved, allowlisted local workflow actions. */
class ScheduledWorkflowWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(KEY_WORKFLOW_ID)
            ?: return Result.failure()
        val workspaceId = inputData.getString(KEY_WORKSPACE_ID)
            ?: return Result.failure()

        return try {
            val database = OracleDatabase.get(applicationContext)
            val triggerDao = database.workflowTriggerDao()
            val trigger = triggerDao.findTrigger(workflowId, workspaceId)
            if (trigger?.enabled != true) return Result.success()

            val workflowDao = database.workflowDao()
            val workflow = workflowDao.findWorkflow(workflowId, workspaceId)
            if (workflow == null) {
                triggerDao.deleteTrigger(workflowId, workspaceId)
                return Result.success()
            }

            val steps = workflowDao.stepsFor(workflowId, workspaceId)
            val documents = DocumentRepository(
                applicationContext,
                database.documentDao(),
                workspaceId
            )
            val runner = LocalWorkflowRunner(
                workflowDao,
                database.audioMemoryDao(),
                documents,
                workspaceId
            )
            // Deterministic workflow failures are written to run history; returning
            // success keeps the next periodic occurrence scheduled.
            runner.execute(workflow, steps, origin = WorkflowRunOrigin.SCHEDULED)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_WORKSPACE_ID = "workspace_id"
        const val TAG = "oracle-scheduled-workflow"
    }
}
