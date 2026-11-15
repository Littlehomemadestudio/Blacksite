package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticSettingsTest {
    @Test
    fun clampsGenerationControlsToSupportedRanges() {
        val settings = AgenticSettings(
            thinkingLevel = "invalid",
            thinkingSummaries = "verbose",
            maxOutputTokens = 999999,
            temperature = 9.0,
            topP = -2.0,
            topK = 999
        ).validated()

        assertEquals("high", settings.thinkingLevel)
        assertEquals("auto", settings.thinkingSummaries)
        assertEquals(32768, settings.maxOutputTokens)
        assertEquals(2.0, settings.temperature, 0.0)
        assertEquals(0.0, settings.topP, 0.0)
        assertEquals(100, settings.topK)
    }

    @Test
    fun preservesValidGeminiControls() {
        val settings = AgenticSettings(
            thinkingLevel = "xhigh",
            thinkingSummaries = "none",
            maxOutputTokens = 16384,
            temperature = 0.5,
            topP = 0.85,
            topK = 40
        ).validated()

        assertEquals("xhigh", settings.thinkingLevel)
        assertEquals("none", settings.thinkingSummaries)
        assertEquals(16384, settings.maxOutputTokens)
        assertEquals(0.5, settings.temperature, 0.0)
        assertEquals(0.85, settings.topP, 0.0)
        assertEquals(40, settings.topK)
    }
}
