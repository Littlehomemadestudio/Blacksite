package studio.oracle.data

import studio.oracle.data.local.WorkflowStepEntity

enum class WorkflowAction(val key: String, val label: String, val help: String) {
    CREATE_NOTE("CREATE_NOTE", "Create local note", "Create a new note in ORACLE's private workspace."),
    APPEND_NOTE("APPEND_NOTE", "Append to local note", "Append text to an existing note by its exact title."),
    SEARCH_LIBRARY("SEARCH_LIBRARY", "Search document library", "Search indexed PDF, TXT and Markdown passages on this device."),
    SEARCH_AUDIO("SEARCH_AUDIO", "Search audio memory", "Search local recording titles, transcripts, summaries, tasks and decisions.");

    companion object {
        fun from(key: String): WorkflowAction? = entries.firstOrNull { it.key == key }
    }
}

object WorkflowRules {
    const val MAX_STEPS = 20
    const val MAX_TITLE_CHARS = 120
    const val MAX_INPUT_CHARS = 12_000

    fun validate(steps: List<WorkflowStepEntity>, expectedWorkflowId: String? = null): String? {
        if (steps.isEmpty()) return "Add at least one step before saving or running this workflow."
        if (steps.size > MAX_STEPS) return "A workflow can contain at most $MAX_STEPS steps."
        if (expectedWorkflowId != null && steps.any { it.workflowId != expectedWorkflowId }) {
            return "Every step must belong to the workflow being executed."
        }

        steps.sortedBy { it.position }.forEachIndexed { index, step ->
            val action = WorkflowAction.from(step.actionType)
                ?: return "Step ${index + 1} uses an unsupported action."
            if (step.title.isBlank()) {
                return when (action) {
                    WorkflowAction.SEARCH_LIBRARY, WorkflowAction.SEARCH_AUDIO -> "Step ${index + 1}: enter a search query."
                    else -> "Step ${index + 1}: enter a note title."
                }
            }
            if (step.title.length > MAX_TITLE_CHARS) return "Step ${index + 1}: title or query is too long."
            if (step.input.length > MAX_INPUT_CHARS) return "Step ${index + 1}: text exceeds the 12,000 character limit."
            when (action) {
                WorkflowAction.CREATE_NOTE, WorkflowAction.APPEND_NOTE ->
                    if (step.input.isBlank()) return "Step ${index + 1}: enter the note text."
                WorkflowAction.SEARCH_LIBRARY, WorkflowAction.SEARCH_AUDIO -> Unit
            }
        }
        return null
    }
}
