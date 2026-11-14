package studio.oracle.data

data class AgentTurn(
    val role: String,
    val content: String
)

data class SubmissionHistory(
    val isRetry: Boolean,
    val history: List<AgentTurn>
)

/**
 * Shared conversation rules used by the persistence and Gemini layers.
 * Keeping this policy pure makes context limits and session naming testable.
 */
object AgentConversationPolicy {
    const val MAX_CONTEXT_MESSAGES = 12
    const val DEFAULT_TITLE = "New thread"
    const val MAX_TITLE_LENGTH = 42

    fun context(turns: List<AgentTurn>): List<Pair<String, String>> =
        turns.takeLast(MAX_CONTEXT_MESSAGES).map { it.role to it.content }

    fun submissionHistory(
        turns: List<AgentTurn>,
        prompt: String,
        failedPrompt: String?,
        failedPromptPersisted: Boolean
    ): SubmissionHistory {
        val lastTurn = turns.lastOrNull()
        val isRetry = failedPromptPersisted &&
            failedPrompt == prompt &&
            lastTurn?.role == "user" &&
            lastTurn.content == prompt
        return SubmissionHistory(
            isRetry = isRetry,
            history = if (isRetry) turns.dropLast(1) else turns
        )
    }

    fun titleFor(prompt: String, maxLength: Int = MAX_TITLE_LENGTH): String {
        require(maxLength > 0) { "Title length must be positive." }
        val normalized = prompt.trim().replace(Regex("\\s+"), " ")
        return normalized.take(maxLength).ifBlank { DEFAULT_TITLE }
    }
}
