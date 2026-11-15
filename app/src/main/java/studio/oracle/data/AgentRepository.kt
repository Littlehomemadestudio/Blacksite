package studio.oracle.data

import studio.oracle.data.local.AgentDao
import studio.oracle.data.local.AgentMessageEntity
import studio.oracle.data.local.AgentSessionEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Local-first agent history. Conversation text stays in Room until the user
 * explicitly submits a turn to Gemini.
 */
class AgentRepository(
    private val dao: AgentDao,
    private val workspaceId: String
) {
    val sessions: Flow<List<AgentSessionEntity>> = dao.observeSessions(workspaceId)

    fun messages(sessionId: String): Flow<List<AgentMessageEntity>> =
        dao.observeMessages(sessionId)

    suspend fun ensureSession(model: String): String =
        dao.sessionsSnapshot(workspaceId).firstOrNull()?.id ?: createSession(model)

    suspend fun createSession(model: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.upsertSession(
            AgentSessionEntity(
                id = id,
                title = AgentConversationPolicy.DEFAULT_TITLE,
                model = model,
                createdAt = now,
                updatedAt = now,
                workspaceId = workspaceId
            )
        )
        return id
    }

    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        model: String
    ) {
        require(role == "user" || role == "assistant") { "Unsupported message role." }
        require(content.isNotBlank()) { "Message content cannot be blank." }
        val now = System.currentTimeMillis()
        dao.insertMessage(
            AgentMessageEntity(
                sessionId = sessionId,
                role = role,
                content = content,
                createdAt = now
            )
        )
        val session = dao.findSession(sessionId) ?: return
        val title = if (role == "user" && session.title == AgentConversationPolicy.DEFAULT_TITLE) {
            AgentConversationPolicy.titleFor(content)
        } else {
            session.title
        }
        dao.updateSession(sessionId, title, model, now)
    }

    suspend fun updateModel(sessionId: String, model: String) {
        dao.updateModel(sessionId, model, System.currentTimeMillis())
    }

    suspend fun clearMessages(sessionId: String) {
        dao.clearMessages(sessionId)
        dao.resetTitle(sessionId, System.currentTimeMillis())
    }

    suspend fun deleteSession(sessionId: String) {
        dao.deleteSession(sessionId)
    }
}
