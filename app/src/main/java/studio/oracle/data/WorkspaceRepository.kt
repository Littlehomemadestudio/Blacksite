package studio.oracle.data

import kotlinx.coroutines.flow.Flow
import studio.oracle.data.local.WorkspaceDao
import studio.oracle.data.local.WorkspaceEntity
import java.util.UUID

object WorkspacePolicy {
    const val DEFAULT_WORKSPACE_ID = "workspace-default"
    const val DEFAULT_WORKSPACE_NAME = "Personal"
    const val MAX_NAME_LENGTH = 36

    fun normalizeName(raw: String): String? {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
    }
}

sealed interface WorkspaceCreateResult {
    data class Success(val workspace: WorkspaceEntity) : WorkspaceCreateResult
    data class Failure(val message: String) : WorkspaceCreateResult
}

/** Local workspace catalogue. Workspace names and membership never leave the device. */
class WorkspaceRepository(private val dao: WorkspaceDao) {
    val workspaces: Flow<List<WorkspaceEntity>> = dao.observeWorkspaces()

    suspend fun ensureDefaultWorkspace() {
        if (dao.findWorkspace(WorkspacePolicy.DEFAULT_WORKSPACE_ID) == null) {
            dao.insertWorkspace(
                WorkspaceEntity(
                    id = WorkspacePolicy.DEFAULT_WORKSPACE_ID,
                    name = WorkspacePolicy.DEFAULT_WORKSPACE_NAME,
                    description = "Your personal workspace",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
        }
    }

    suspend fun create(rawName: String): WorkspaceCreateResult {
        val name = WorkspacePolicy.normalizeName(rawName)
            ?: return WorkspaceCreateResult.Failure(
                "Enter a workspace name up to " + WorkspacePolicy.MAX_NAME_LENGTH + " characters."
            )
        if (dao.countName(name) > 0) {
            return WorkspaceCreateResult.Failure("A workspace with that name already exists.")
        }

        val now = System.currentTimeMillis()
        val workspace = WorkspaceEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = "Created on this device",
            createdAt = now,
            updatedAt = now
        )
        dao.insertWorkspace(workspace)
        return WorkspaceCreateResult.Success(workspace)
    }

    suspend fun rename(id: String, rawName: String): WorkspaceCreateResult {
        if (id == WorkspacePolicy.DEFAULT_WORKSPACE_ID) {
            return WorkspaceCreateResult.Failure("The Personal workspace cannot be renamed.")
        }
        val name = WorkspacePolicy.normalizeName(rawName)
            ?: return WorkspaceCreateResult.Failure(
                "Enter a workspace name up to " + WorkspacePolicy.MAX_NAME_LENGTH + " characters."
            )
        if (dao.countName(name) > 0) {
            val current = dao.findWorkspace(id)
            if (current?.name?.equals(name, ignoreCase = true) != true) {
                return WorkspaceCreateResult.Failure("A workspace with that name already exists.")
            }
        }
        val updated = dao.renameWorkspace(id, name, System.currentTimeMillis())
        val workspace = dao.findWorkspace(id)
        return if (updated == 1 && workspace != null) {
            WorkspaceCreateResult.Success(workspace)
        } else {
            WorkspaceCreateResult.Failure("That workspace no longer exists.")
        }
    }
}
