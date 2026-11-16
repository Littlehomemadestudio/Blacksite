package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAudioTranscriberTest {
    private val transcriber = GeminiAudioTranscriber()

    @Test
    fun parsesTranscriptSummaryTasksAndDecisions() {
        val result = transcriber.parseAnalysis(
            """{"transcript":"We should ship on Friday.","summary":"Release planning.","tasks":["Prepare the build"],"decisions":["Ship Friday"]}"""
        )
        assertEquals("We should ship on Friday.", result.transcript)
        assertEquals("Release planning.", result.summary)
        assertEquals(listOf("Prepare the build"), result.tasks)
        assertEquals(listOf("Ship Friday"), result.decisions)
    }

    @Test
    fun acceptsWhitespaceAroundAValidJsonObject() {
        val result = transcriber.parseAnalysis(
            """  {"transcript":"سلام","summary":"","tasks":["تمرین"],"decisions":[]}  """
        )
        assertEquals("سلام", result.transcript)
        assertEquals(listOf("تمرین"), result.tasks)
        assertTrue(result.decisions.isEmpty())
    }

    @Test
    fun usesDedicatedTranscriptionTextWhenAnalysisContainsOnlyInsights() {
        val result = transcriber.parseAnalysis(
            """{"summary":"Planning call","tasks":["Send the agenda"],"decisions":["Meet Monday"]}""",
            "Original spoken words."
        )
        assertEquals("Original spoken words.", result.transcript)
        assertEquals("Planning call", result.summary)
    }

    @Test
    fun rejectsAnEmptyTranscript() {
        val result = runCatching {
            transcriber.parseAnalysis("""{"transcript":" ","summary":"empty","tasks":[],"decisions":[]}""")
        }
        assertTrue(result.isFailure)
    }
}
