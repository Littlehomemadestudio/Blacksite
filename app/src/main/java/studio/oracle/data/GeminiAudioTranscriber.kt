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
 * Uploads one user-approved WAV to Google's Gemini Files API, analyzes it, and
 * attempts remote deletion even when the request is cancelled or fails.
 */
class GeminiAudioTranscriber(
    private val uploader: GeminiFileUploader = GeminiFileUploader(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
) {
    suspend fun analyze(apiKey: String, model: String, file: File): GeminiAudioAnalysisResult {
        require(apiKey.isNotBlank()) { "Connect a Gemini API key in Settings first." }
        require(model.matches(Regex("[A-Za-z0-9._-]+"))) { "The selected Gemini model identifier is invalid." }
        require(file.isFile && file.length() in 45L..AudioMemoryRepository.MAX_FILE_BYTES) {
            "This recording is missing, empty, or exceeds the 30 MB upload limit."
        }

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val uploaded = uploader.upload(apiKey, file.nameWithoutExtension, "audio/wav", bytes)
        var remoteDeleted = false
        val analysis = try {
            val prompt = """
                Transcribe the attached audio and return only one valid JSON object with exactly these keys:
                "transcript", "summary", "tasks", "decisions".
                Preserve the spoken language; do not translate. Make the transcript as verbatim as practical.
                Use [inaudible] for genuinely unintelligible speech. Do not invent speaker identities, tasks, or decisions.
                "summary" must be concise. "tasks" and "decisions" must be arrays of short strings.
                If there are no tasks or decisions, return empty arrays.
            """.trimIndent()
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray()
                                    .put(JSONObject().put("text", prompt))
                                    .put(
                                        JSONObject().put(
                                            "file_data",
                                            JSONObject()
                                                .put("mime_type", uploaded.mimeType.ifBlank { "audio/wav" })
                                                .put("file_uri", uploaded.uri)
                                        )
                                    )
                            )
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.1)
                        .put("maxOutputTokens", 8192)
                        .put("responseMimeType", "application/json")
                )
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            http.newCall(request).await().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(apiError(response.code, raw))
                val root = JSONObject(raw)
                val candidates = root.optJSONArray("candidates") ?: JSONArray()
                val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: JSONArray()
                val responseText = buildString {
                    for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index)?.optString("text").orEmpty()
                        if (part.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(part)
                        }
                    }
                }
                parseAnalysis(responseText)
            }
        } finally {
            withContext(NonCancellable) {
                remoteDeleted = runCatching { uploader.delete(apiKey, uploaded.name) }.getOrDefault(false)
            }
        }
        GeminiAudioAnalysisResult(analysis, remoteDeleted)
    }

    internal fun parseAnalysis(responseText: String): AudioAnalysis {
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
        val transcript = root.optString("transcript").trim()
        if (transcript.isBlank() || transcript == "null") {
            throw IOException("Gemini did not return a usable transcript.")
        }
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

    private fun apiError(code: Int, raw: String): String = when (code) {
        400 -> "Gemini could not process this audio or the selected model does not support audio input."
        401, 403 -> "Gemini rejected the API key. Reconnect it in Settings."
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
