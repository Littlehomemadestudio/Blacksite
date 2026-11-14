package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentConversationPolicyTest {
    @Test
    fun contextKeepsOnlyTheMostRecentTwelveTurnsInOrder() {
        val turns = (1..15).map { AgentTurn(if (it % 2 == 0) "assistant" else "user", "turn-$it") }

        val context = AgentConversationPolicy.context(turns)

        assertEquals(12, context.size)
        assertEquals("turn-4", context.first().second)
        assertEquals("turn-15", context.last().second)
    }

    @Test
    fun titleNormalizesWhitespaceAndTruncates() {
        val title = AgentConversationPolicy.titleFor("  Build   a\n\tbetter   workspace  ", maxLength = 18)

        assertEquals("Build a better wor", title)
    }

    @Test
    fun blankPromptUsesNewThreadTitle() {
        assertEquals(AgentConversationPolicy.DEFAULT_TITLE, AgentConversationPolicy.titleFor(" \n "))
    }

    @Test
    fun retryReusesOnlyThePersistedFailedUserTurn() {
        val turns = listOf(
            AgentTurn("user", "previous question"),
            AgentTurn("assistant", "previous answer"),
            AgentTurn("user", "failed question")
        )

        val submission = AgentConversationPolicy.submissionHistory(
            turns = turns,
            prompt = "failed question",
            failedPrompt = "failed question",
            failedPromptPersisted = true
        )

        assertEquals(true, submission.isRetry)
        assertEquals(turns.dropLast(1), submission.history)
    }

    @Test
    fun identicalPromptIsNotARepeatWhenItWasNeverPersisted() {
        val turns = listOf(AgentTurn("user", "same question"))

        val submission = AgentConversationPolicy.submissionHistory(
            turns = turns,
            prompt = "same question",
            failedPrompt = "same question",
            failedPromptPersisted = false
        )

        assertEquals(false, submission.isRetry)
        assertEquals(turns, submission.history)
    }

    @Test
    fun aDifferentPromptDoesNotReuseThePreviousFailedTurn() {
        val turns = listOf(AgentTurn("user", "failed question"))

        val submission = AgentConversationPolicy.submissionHistory(
            turns = turns,
            prompt = "new question",
            failedPrompt = "failed question",
            failedPromptPersisted = true
        )

        assertEquals(false, submission.isRetry)
        assertEquals(turns, submission.history)
    }

}
