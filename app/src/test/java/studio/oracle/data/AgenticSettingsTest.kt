package studio.oracle.data

import org.junit.Assert.assertEquals
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

        assertEquals("high", settings.thinkingLevel)
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
}
