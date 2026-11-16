package studio.oracle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AudioAnalysis(
    val transcript: String,
    val summary: String,
    val tasks: List<String>,
    val decisions: List<String>
)

data class GeminiAudioAnalysisResult(
    val analysis: AudioAnalysis,
    val remoteFileDeleted: Boolean
)

/**
 * Uses Gemini 3.5 Transcribe for speech recognition, then the user's selected
 * Gemini model for summary, task, and decision extraction.
 */
class GeminiAudioTranscriber(
    private val uploader: GeminiFileUploader = GeminiFileUploader(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
) {
    suspend fun analyze(apiKey: String, analysisModel: String, file: File): GeminiAudioAnalysisResult {
        require(apiKey.isNotBlank()) { "Connect a Gemini API key in Settings first." }
        require(analysisModel.matches(Regex("[A-Za-z0-9._-]+"))) { "The selected Gemini model identifier is invalid." }
        require(file.isFile && file.length() in 45L..AudioMemoryRepository.MAX_FILE_BYTES) {
            "This recording is missing, empty, or exceeds the 30 MB upload limit."
        }

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val uploaded = uploader.upload(apiKey, file.nameWithoutExtension, "audio/wav", bytes)
        var remoteFileDeleted = false
        val transcript = try {
            transcribeUploadedFile(apiKey, uploaded.uri, uploaded.mimeType.ifBlank { "audio/wav" })
        } finally {
            withContext(NonCancellable) {
                remoteFileDeleted = runCatching {
                    uploader.delete(apiKey, uploaded.name)
                }.getOrDefault(false)
            }
        }
        val analysis = summarizeTranscript(apiKey, analysisModel, transcript)
        return GeminiAudioAnalysisResult(analysis, remoteFileDeleted)
    }

    private suspend fun transcribeUploadedFile(
        apiKey: String,
        fileUri: String,
        mimeType: String
    ): String {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "fileData",
                                JSONObject()
                                    .put("fileUri", fileUri)
                                    .put("mimeType", mimeType)
                            )
                        )
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject().put(
                    "audioTranscriptionConfig",
                    JSONObject()
                        .put("languageCodes", JSONArray())
                        .put("mode", "VERBATIM")
                )
            )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return http.newCall(request).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(apiError(response.code, raw))
            val text = extractCandidateText(raw)
            if (text.isBlank()) throw IOException("Gemini Transcribe returned an empty transcript.")
            text.trim()
        }
    }

    private suspend fun summarizeTranscript(
        apiKey: String,
        model: String,
        transcript: String
    ): AudioAnalysis {
        val prompt = """
            Analyze the following transcript. Return only one valid JSON object with exactly these keys:
            "summary", "tasks", "decisions".
            Keep the summary concise. "tasks" must contain only explicit actionable commitments.
            "decisions" must contain only decisions clearly made in the conversation.
            Do not infer owners, dates, or commitments that are not stated. Use empty arrays when none exist.
            Preserve the original transcript separately; do not rewrite or translate it.

            TRANSCRIPT:
            $transcript
        """.trimIndent()
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("maxOutputTokens", 4096)
                    .put("responseMimeType", "application/json")
            )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return http.newCall(request).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(apiError(response.code, raw))
            parseAnalysis(extractCandidateText(raw), transcript)
        }
    }

    internal fun parseAnalysis(responseText: String, sourceTranscript: String = ""): AudioAnalysis {
        val start = responseText.indexOf('{')
        val end = responseText.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IOException("Gemini returned an unreadable audio analysis. Retry transcription.")
        }
        val root = try {
            JSONObject(responseText.substring(start, end + 1))
        } catch (_: Exception) {
            throw IOException("Gemini returned invalid analysis data. Retry transcription.")
        }
        val returnedTranscript = root.optString("transcript").trim()
        val transcript = returnedTranscript.takeIf { it.isNotBlank() && it != "null" }
            ?: sourceTranscript.trim()
        if (transcript.isBlank()) throw IOException("Gemini did not return a usable transcript.")
        return AudioAnalysis(
            transcript = transcript,
            summary = root.optString("summary").trim().takeIf { it != "null" }.orEmpty(),
            tasks = readStringArray(root.optJSONArray("tasks")),
            decisions = readStringArray(root.optJSONArray("decisions"))
        )
    }

    private fun readStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { add(it.take(500)) }
            }
        }.distinct().take(40)
    }

    private fun extractCandidateText(raw: String): String {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates") ?: JSONArray()
        val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: JSONArray()
        return buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index)?.optString("text").orEmpty()
                if (part.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(part)
                }
            }
        }
    }

    private fun apiError(code: Int, raw: String): String = when (code) {
        400 -> "Gemini could not process this audio or the selected model rejected the request."
        401, 403 -> "Gemini rejected the API key. Reconnect it in Settings."
        404 -> "The Gemini transcription or analysis model is unavailable for this API key."
        429 -> "Gemini rate limit or quota reached. Check usage and retry."
        else -> {
            val detail = runCatching {
                JSONObject(raw).optJSONObject("error")?.optString("message")
            }.getOrNull()
            detail?.takeIf { it.isNotBlank() }?.take(220) ?: "Gemini audio analysis failed (HTTP $code)."
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            continuation.resumeWith(Result.failure(error))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, resource, _ -> resource.close() }
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
