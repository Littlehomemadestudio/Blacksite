package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticSettingsTest {
    @Test
    fun clampsGenerationControlsToSupportedRanges() {
        val settings = AgenticSettings(
            thinkingLevel = "invalid",
            thinkingSummaries = "verbose",
            maxOutputTokens = 999999,
            toolChoice = "invalid"
        ).validated()

        assertEquals("medium", settings.thinkingLevel)
        assertEquals("auto", settings.thinkingSummaries)
        assertEquals(65536, settings.maxOutputTokens)
        assertEquals("auto", settings.toolChoice)
    }

    @Test
    fun preservesValidGeminiControls() {
        val settings = AgenticSettings(
            thinkingLevel = "xhigh",
            thinkingSummaries = "none",
            maxOutputTokens = 16384,
            toolChoice = "none"
        ).validated()

        assertEquals("xhigh", settings.thinkingLevel)
        assertEquals("none", settings.thinkingSummaries)
        assertEquals(16384, settings.maxOutputTokens)
        assertEquals("none", settings.toolChoice)
    }

    @Test
    fun hostedExecutionAndUrlContextRequireExplicitOptIn() {
        val defaults = AgenticSettings()

        assertFalse(defaults.hostedPython)
        assertFalse(defaults.urlContext)
    }

    @Test
    fun agentRunStopsAfterSixteenModelRounds() {
        val budget = AgenticExecutionBudget()

        repeat(AgenticExecutionBudget.MAX_ROUNDS) {
            assertTrue(budget.beginRound())
        }

        assertFalse(budget.beginRound())
        assertEquals(AgenticExecutionBudget.MAX_ROUNDS, budget.roundsUsed)
    }

    @Test
    fun toolCallBatchIsReservedAtomicallyWithinBudget() {
        val budget = AgenticExecutionBudget(maxRounds = 2, maxToolCalls = 3)

        assertTrue(budget.reserveToolCalls(2))
        assertFalse(budget.reserveToolCalls(2))
        assertEquals(2, budget.toolCallsUsed)
        assertTrue(budget.reserveToolCalls(1))
        assertFalse(budget.reserveToolCalls(1))
        assertEquals(3, budget.toolCallsUsed)
    }

    @Test
    fun rejectsNegativeToolReservationsWithoutChangingBudget() {
        val budget = AgenticExecutionBudget(maxRounds = 2, maxToolCalls = 4)

        assertFalse(budget.reserveToolCalls(-1))
        assertEquals(0, budget.toolCallsUsed)
        assertTrue(budget.reserveToolCalls(0))
    }

    @Test
    fun sessionApprovalDoesNotCrossCapabilityGroupsOrConversations() {
        val pythonGrant = AgenticPermissionPolicy.sessionGrantKey("chat-1", "python")

        assertNotEquals(pythonGrant, AgenticPermissionPolicy.sessionGrantKey("chat-1", "phone_files_read"))
        assertNotEquals(pythonGrant, AgenticPermissionPolicy.sessionGrantKey("chat-1", "phone_files_write"))
        assertNotEquals(pythonGrant, AgenticPermissionPolicy.sessionGrantKey("chat-1", "web_search"))
        assertNotEquals(pythonGrant, AgenticPermissionPolicy.sessionGrantKey("chat-2", "python"))
    }
}
