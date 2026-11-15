package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.oracle.data.local.WorkflowStepEntity

class WorkflowRulesTest {
    private fun step(
        action: String = WorkflowAction.CREATE_NOTE.key,
        title: String = "Study plan",
        input: String = "Review chapter one."
    ) = WorkflowStepEntity(
        id = "step-1",
        workflowId = "workflow-1",
        position = 0,
        actionType = action,
        title = title,
        input = input
    )

    @Test
    fun acceptsAValidLocalNoteWorkflow() {
        assertNull(WorkflowRules.validate(listOf(step())))
    }

    @Test
    fun rejectsEmptyPlansAndMoreThanTwentySteps() {
        assertTrue(WorkflowRules.validate(emptyList())!!.contains("at least one"))
        val tooMany = (0..WorkflowRules.MAX_STEPS).map {
            step().copy(id = "step-$it", position = it)
        }
        assertTrue(WorkflowRules.validate(tooMany)!!.contains("at most"))
    }

    @Test
    fun rejectsUnknownActionsInsteadOfExecutingThem() {
        assertTrue(WorkflowRules.validate(listOf(step(action = "RUN_SHELL")))!!.contains("unsupported"))
    }

    @Test
    fun noteActionsRequireBothTitleAndBody() {
        assertTrue(WorkflowRules.validate(listOf(step(title = "  ")))!!.contains("note title"))
        assertTrue(WorkflowRules.validate(listOf(step(input = "  ")))!!.contains("note text"))
        assertNull(WorkflowRules.validate(listOf(step(action = WorkflowAction.APPEND_NOTE.key))))
    }

    @Test
    fun documentSearchRequiresAQueryButNoBody() {
        assertTrue(
            WorkflowRules.validate(
                listOf(step(action = WorkflowAction.SEARCH_LIBRARY.key, title = " "))
            )!!.contains("search query")
        )
        assertNull(
            WorkflowRules.validate(
                listOf(step(action = WorkflowAction.SEARCH_LIBRARY.key, title = "quadratic equation", input = ""))
            )
        )
    }

    @Test
    fun rejectsStepsFromAnotherWorkflowWhenAnExecutionTargetIsProvided() {
        val foreignStep = step().copy(workflowId = "another-workflow")
        assertTrue(
            WorkflowRules.validate(listOf(foreignStep), expectedWorkflowId = "workflow-1")
                ?.contains("belong to the workflow") == true
        )
        assertNull(WorkflowRules.validate(listOf(foreignStep)))
    }

    @Test
    fun enforcesInputBounds() {
        val oversized = step(input = "x".repeat(WorkflowRules.MAX_INPUT_CHARS + 1))
        assertTrue(WorkflowRules.validate(listOf(oversized))!!.contains("12,000"))
        assertEquals(20, WorkflowRules.MAX_STEPS)
    }
}
