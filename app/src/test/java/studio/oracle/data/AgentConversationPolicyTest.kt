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
}
