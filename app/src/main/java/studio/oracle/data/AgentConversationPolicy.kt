package studio.oracle.data

data class AgentTurn(
    val role: String,
    val content: String
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

    fun titleFor(prompt: String, maxLength: Int = MAX_TITLE_LENGTH): String {
        require(maxLength > 0) { "Title length must be positive." }
        val normalized = prompt.trim().replace(Regex("\\s+"), " ")
        return normalized.take(maxLength).ifBlank { DEFAULT_TITLE }
    }
}
