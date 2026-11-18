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
    fun buildsTheDocumentedInteractionsAudioRequest() {
        val body = transcriber.buildTranscriptionRequestBody(
            "https://generativelanguage.googleapis.com/v1beta/files/audio-123",
            "audio/wav"
        )
        assertEquals("gemini-3.5-transcribe", body.getString("model"))
        val audio = body.getJSONArray("input").getJSONObject(0)
        assertEquals("audio", audio.getString("type"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/audio-123", audio.getString("uri"))
        assertEquals("audio/wav", audio.getString("mime_type"))
        val transcription = body.getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals(0, transcription.getJSONArray("language_codes").length())
        assertTrue(!body.has("contents"))
    }

    @Test
    fun extractsOnlyModelOutputFromInteractionsResponse() {
        val transcript = transcriber.extractInteractionText(
            """{"status":"completed","steps":[{"type":"thought","summary":[{"type":"text","text":"Ignore this internal summary."}]},{"type":"model_output","content":[{"type":"text","text":"First sentence."},{"type":"text","text":"Second sentence."}]}]}"""
        )
        assertEquals("First sentence.\nSecond sentence.", transcript)
    }

    @Test
    fun prefersInteractionsOutputTextWhenPresent() {
        val transcript = transcriber.extractInteractionText(
            """{"status":"completed","output_text":"Canonical transcript","steps":[{"type":"model_output","content":[{"type":"text","text":"Different text"}]}]}"""
        )
        assertEquals("Canonical transcript", transcript)
    }

    @Test
    fun rejectsInteractionsThatDidNotComplete() {
        val result = runCatching {
            transcriber.extractInteractionText(
                """{"status":"requires_action","steps":[{"type":"thought"}]}"""
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun retriesOnlyTransientHttpStatuses() {
        assertTrue(transcriber.isRetryableHttpStatus(408))
        assertTrue(transcriber.isRetryableHttpStatus(425))
        assertTrue(transcriber.isRetryableHttpStatus(429))
        assertTrue(transcriber.isRetryableHttpStatus(503))
        assertTrue(!transcriber.isRetryableHttpStatus(400))
        assertTrue(!transcriber.isRetryableHttpStatus(401))
    }

    @Test
    fun rejectsAnEmptyTranscript() {
        val result = runCatching {
            transcriber.parseAnalysis("""{"transcript":" ","summary":"empty","tasks":[],"decisions":[]}""")
        }
        assertTrue(result.isFailure)
    }
}
