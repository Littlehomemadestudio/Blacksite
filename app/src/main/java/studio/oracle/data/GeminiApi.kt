package studio.oracle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface GeminiConnectionResult {
    data class Connected(val message: String) : GeminiConnectionResult
    data class Failed(val message: String) : GeminiConnectionResult
}

sealed interface GeminiReplyResult {
    data class Success(val text: String, val model: String) : GeminiReplyResult
    data class Failed(val message: String) : GeminiReplyResult
}

class GeminiApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()
) {
    suspend fun validateKey(apiKey: String): GeminiConnectionResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiConnectionResult.Failed("Enter a Gemini API key first.")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> GeminiConnectionResult.Connected("Gemini API key verified.")
                    response.code == 401 || response.code == 403 -> GeminiConnectionResult.Failed("The key was rejected. Check the key and its API access.")
                    response.code == 429 -> GeminiConnectionResult.Failed("Gemini rate limit reached. Try again later.")
                    else -> GeminiConnectionResult.Failed("Gemini returned HTTP ${response.code}.")
                }
            }
        } catch (_: IOException) {
            GeminiConnectionResult.Failed("Could not reach Gemini. Check your connection and try again.")
        } catch (_: Exception) {
            GeminiConnectionResult.Failed("Connection validation failed. Please try again.")
        }
    }

    /** Sends only the user-approved conversation to Google's Gemini generateContent endpoint. */
    suspend fun generate(
        apiKey: String,
        prompt: String,
        history: List<Pair<String, String>>,
        model: String = "gemini-2.5-flash"
    ): GeminiReplyResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiReplyResult.Failed("Connect a Gemini API key in Settings first.")
        if (prompt.isBlank()) return@withContext GeminiReplyResult.Failed("Write an instruction before running ORACLE.")
        val contents = JSONArray()
        history.takeLast(12).forEach { (role, text) ->
            contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put(
                "text", "You are ORACLE, a precise and capable personal AI operator inside an Android workspace. Help plan and execute work through clear, safe instructions. You do not have access to device files, apps, or external tools unless explicitly provided by the app. Never claim to have performed an action you did not perform. For multi-step requests, provide a numbered plan, identify any required user approval, then give the next actionable step. Be concise but useful."
            ))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.45).put("maxOutputTokens", 4096))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                    return@withContext GeminiReplyResult.Failed(
                        when (response.code) {
                            401, 403 -> "Gemini rejected the API key. Reconnect it in Settings."
                            429 -> "Gemini rate limit or quota reached. Check usage and retry."
                            else -> detail?.takeIf { it.isNotBlank() }?.take(220) ?: "Gemini request failed (HTTP ${response.code})."
                        }
                    )
                }
                val root = JSONObject(raw)
                val candidates = root.optJSONArray("candidates")
                val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = buildString {
                    if (parts != null) for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (part.isNotBlank()) { if (isNotEmpty()) append("\n"); append(part) }
                    }
                }
                if (text.isBlank()) GeminiReplyResult.Failed("Gemini returned no text. Try a different instruction.")
                else GeminiReplyResult.Success(text, model)
            }
        } catch (_: IOException) {
            GeminiReplyResult.Failed("Network error while contacting Gemini. Check your connection and retry.")
        } catch (_: Exception) {
            GeminiReplyResult.Failed("Could not read Gemini's response. Please retry.")
        }
    }
}
