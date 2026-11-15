package studio.oracle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

data class GeminiModel(
    val name: String,
    val displayName: String,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val description: String? = null
)

sealed interface GeminiModelsResult {
    data class Success(val models: List<GeminiModel>) : GeminiModelsResult
    data class Failed(val message: String) : GeminiModelsResult
}

class GeminiApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()
) {
    suspend fun validateKey(apiKey: String): GeminiConnectionResult {
        if (apiKey.isBlank()) return GeminiConnectionResult.Failed("Enter a Gemini API key first.")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        return try {
            http.newCall(request).await().use { response ->
                when {
                    response.isSuccessful -> GeminiConnectionResult.Connected("Gemini API key verified.")
                    else -> GeminiConnectionResult.Failed(connectionError(response.code))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GeminiConnectionResult.Failed("Could not reach Gemini. Check your connection and try again.")
        } catch (_: Exception) {
            GeminiConnectionResult.Failed("Connection validation failed. Please try again.")
        }
    }

    /** Fetches the models actually exposed to this key, rather than relying on a stale local list. */
    suspend fun listModels(apiKey: String): GeminiModelsResult {
        if (apiKey.isBlank()) return GeminiModelsResult.Failed("Connect a Gemini API key in Settings first.")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        return try {
            http.newCall(request).await().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    GeminiModelsResult.Failed(apiError(response.code, raw))
                } else {
                    val entries = JSONObject(raw).optJSONArray("models") ?: JSONArray()
                    val models = buildList {
                        for (index in 0 until entries.length()) {
                            val item = entries.optJSONObject(index) ?: continue
                            val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                            val supportsGenerate = (0 until methods.length()).any {
                                methods.optString(it) == "generateContent"
                            }
                            val name = item.optString("name").removePrefix("models/")
                            if (supportsGenerate && name.isNotBlank()) {
                                add(
                                    GeminiModel(
                                        name = name,
                                        displayName = item.optString("displayName").ifBlank { name },
                                        inputTokenLimit = item.optInt("inputTokenLimit").takeIf { item.has("inputTokenLimit") },
                                        outputTokenLimit = item.optInt("outputTokenLimit").takeIf { item.has("outputTokenLimit") },
                                        description = item.optString("description").takeIf { it.isNotBlank() }
                                    )
                                )
                            }
                        }
                    }.sortedWith(compareBy<GeminiModel>({ !it.name.contains("flash", ignoreCase = true) }, { it.displayName }))
                    GeminiModelsResult.Success(models)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GeminiModelsResult.Failed("Network error while loading Gemini models.")
        } catch (_: Exception) {
            GeminiModelsResult.Failed("Could not read Gemini's model list. Please retry.")
        }
    }

    /** Sends only the user-approved conversation to Google's Gemini generateContent endpoint. */
    suspend fun generate(
        apiKey: String,
        prompt: String,
        history: List<Pair<String, String>>,
        model: String = ModelPreferences.DEFAULT_MODEL
    ): GeminiReplyResult {
        if (apiKey.isBlank()) return GeminiReplyResult.Failed("Connect a Gemini API key in Settings first.")
        if (prompt.isBlank()) return GeminiReplyResult.Failed("Write an instruction before running ORACLE.")
        if (!model.matches(Regex("[A-Za-z0-9._-]+"))) {
            return GeminiReplyResult.Failed("The selected Gemini model identifier is invalid.")
        }

        val contents = JSONArray()
        history.takeLast(AgentConversationPolicy.MAX_CONTEXT_MESSAGES).forEach { (role, text) ->
            contents.put(
                JSONObject()
                    .put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "You are ORACLE, a precise personal AI operator inside an Android workspace. " +
                                "You do not have access to device files, apps, or external tools unless the app explicitly provides them. " +
                                "Never claim to have performed an action you did not perform. " +
                                "For multi-step requests, provide a numbered plan, identify required user approval, and give the next actionable step. " +
                                "Be concise but useful."
                        )
                    )
                )
            )
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.45).put("maxOutputTokens", 4096))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            http.newCall(request).await().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    GeminiReplyResult.Failed(apiError(response.code, raw))
                } else {
                    val root = JSONObject(raw)
                    val candidates = root.optJSONArray("candidates")
                    val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val text = buildString {
                        if (parts != null) {
                            for (index in 0 until parts.length()) {
                                val part = parts.optJSONObject(index)?.optString("text").orEmpty()
                                if (part.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append(part)
                                }
                            }
                        }
                    }
                    if (text.isBlank()) {
                        GeminiReplyResult.Failed("Gemini returned no text. Try a different instruction.")
                    } else {
                        GeminiReplyResult.Success(text, model)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GeminiReplyResult.Failed("Network error while contacting Gemini. Check your connection and retry.")
        } catch (_: Exception) {
            GeminiReplyResult.Failed("Could not read Gemini's response. Please retry.")
        }
    }

    private fun connectionError(code: Int): String = when (code) {
        401, 403 -> "The key was rejected. Check the key and its API access."
        429 -> "Gemini rate limit reached. Try again later."
        else -> "Gemini returned HTTP " + code + "."
    }

    private fun apiError(code: Int, raw: String): String {
        return when (code) {
            401, 403 -> "Gemini rejected the API key. Reconnect it in Settings."
            429 -> "Gemini rate limit or quota reached. Check usage and retry."
            else -> {
                val detail = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull()
                detail?.takeIf { it.isNotBlank() }?.take(220)
                    ?: "Gemini request failed (HTTP " + code + ")."
            }
        }
    }
}

/** OkHttp calls are cancelled when the owning coroutine is cancelled. */
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
