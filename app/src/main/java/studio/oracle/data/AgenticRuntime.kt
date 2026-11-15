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

data class AgenticSettings(
    val thinkingLevel: String = "medium",
    val thinkingSummaries: String = "auto",
    val maxOutputTokens: Int = 8192,
    val toolChoice: String = "auto",
    val hostedPython: Boolean = true,
    val urlContext: Boolean = true,
    val webSearch: Boolean = true,
    val phoneFiles: Boolean = true,
    val localPython: Boolean = true,
    val localTools: Boolean = true
) {
    fun validated() = copy(
        thinkingLevel = thinkingLevel.takeIf { it in setOf("low", "medium", "high", "xhigh") } ?: "medium",
        thinkingSummaries = thinkingSummaries.takeIf { it in setOf("auto", "none") } ?: "auto",
        maxOutputTokens = maxOutputTokens.coerceIn(256, 65536),
        toolChoice = toolChoice.takeIf { it in setOf("auto", "none") } ?: "auto"
    )
}

/** A safe, user-visible execution log item. It is not the model's private chain-of-thought. */
data class AgenticEvent(
    val kind: String,
    val title: String,
    val detail: String = "",
    val code: String = "",
    val payload: String = ""
)

data class AgentMediaInput(
    val name: String,
    val mimeType: String,
    val base64Data: String = "",
    val fileUri: String? = null
) {
    fun toContentBlock(): JSONObject {
        val contentType = when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("audio/") -> "audio"
            mimeType.startsWith("video/") -> "video"
            mimeType == "application/pdf" || mimeType == "text/csv" -> "document"
            else -> "document"
        }
        return JSONObject().apply {
            put("type", contentType)
            put("mime_type", mimeType)
            if (!fileUri.isNullOrBlank()) put("uri", fileUri) else put("data", base64Data)
        }
    }
}

sealed interface AgenticResult {
    data class Success(val text: String, val model: String, val interactionId: String?) : AgenticResult
    data class Failed(val message: String) : AgenticResult
}

interface AgenticToolExecutor {
    fun declarations(): JSONArray
    suspend fun execute(name: String, arguments: JSONObject): String
}

/**
 * Model-directed Gemini Interactions loop:
 * model -> function calls -> local execution -> function results -> model.
 * The loop is bounded, cancellable, and logs actions/results, never private thoughts.
 */
class GeminiAgenticRuntime(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()
) {
    suspend fun run(
        apiKey: String,
        prompt: String,
        model: String,
        settings: AgenticSettings,
        executor: AgenticToolExecutor,
        media: List<AgentMediaInput> = emptyList(),
        onEvent: (AgenticEvent) -> Unit = {}
    ): AgenticResult {
        if (apiKey.isBlank()) return AgenticResult.Failed("Connect a Gemini API key in Settings.")
        if (prompt.isBlank() && media.isEmpty()) return AgenticResult.Failed("Write a message or attach media.")
        if (!model.matches(Regex("[A-Za-z0-9._-]+"))) return AgenticResult.Failed("Invalid Gemini model identifier.")

        val cfg = settings.validated()
        val tools = JSONArray().apply {
            if (cfg.hostedPython) put(JSONObject().put("type", "code_execution"))
            if (cfg.urlContext) put(JSONObject().put("type", "url_context"))
            if (cfg.localTools) {
                val declared = executor.declarations()
                for (i in 0 until declared.length()) put(declared.getJSONObject(i))
            }
        }

        var interactionId: String? = null
        var input: Any = makeInput(prompt, media)
        val transcript = StringBuilder()
        onEvent(AgenticEvent("plan", "ORACLE is working", "Choosing the tools and steps needed to complete your request."))

        repeat(MAX_TOOL_ROUNDS) { round ->
            val body = JSONObject()
                .put("model", model)
                .put("input", input)
                .put("tools", tools)
                .put(
                    "system_instruction",
                    "You are ORACLE, a capable multimodal AI operator inside an Android workspace. " +
                        "Use tools when they materially help; execute tasks instead of only explaining how. " +
                        "For multi-step work, plan briefly, perform tool calls, inspect their actual results, and continue until complete. " +
                        "Never claim an action succeeded unless a tool result confirms it. " +
                        "Phone file tools are limited to the folder the user granted. " +
                        "The app obtains approval for file access and local Python once per session or once per action. " +
                        "You may use the local Python tool for normal Python work, including standard-library networking and file downloads. " +
                        "Keep progress updates brief. Do not output private chain-of-thought; provide a concise user-facing summary and final deliverable."
                )
                .put(
                    "generation_config",
                    JSONObject()
                        .put("thinking_level", cfg.thinkingLevel)
                        .put("thinking_summaries", cfg.thinkingSummaries)
                        .put("max_output_tokens", cfg.maxOutputTokens)
                        .put("tool_choice", cfg.toolChoice)
                )
            if (model.contains("flash-image", ignoreCase = true)) {
                body.put(
                    "response_format",
                    JSONArray()
                        .put(JSONObject().put("type", "text"))
                        .put(
                            JSONObject()
                                .put("type", "image")
                                .put("mime_type", "image/png")
                                .put("aspect_ratio", "16:9")
                                .put("image_size", "1K")
                                .put("delivery", "inline")
                        )
                )
            }
            interactionId?.let { body.put("previous_interaction_id", it) }

            val request = Request.Builder()
                .url(INTERACTIONS_URL)
                .header("x-goog-api-key", apiKey)
                .header("Api-Revision", API_REVISION)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val root = try {
                http.newCall(request).await().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return AgenticResult.Failed(apiError(response.code, raw))
                    JSONObject(raw)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return AgenticResult.Failed("Network error contacting Gemini Interactions API.")
            } catch (error: Exception) {
                return AgenticResult.Failed(error.message?.take(240) ?: "Could not read Gemini interaction.")
            }

            interactionId = root.optString("id").takeIf { it.isNotBlank() } ?: interactionId
            root.optJSONObject("usage")?.let { usage ->
                val ledger = listOf(
                    "input" to "total_input_tokens",
                    "output" to "total_output_tokens",
                    "thinking" to "total_thought_tokens",
                    "cached" to "total_cached_tokens",
                    "tools" to "total_tool_use_tokens"
                ).mapNotNull { (label, key) ->
                    if (usage.has(key)) label + " " + usage.optInt(key) else null
                }.joinToString("  ·  ")
                if (ledger.isNotBlank()) onEvent(AgenticEvent("usage", "Context usage", ledger))
            }

            val steps = root.optJSONArray("steps") ?: JSONArray()
            val hasFunctionCalls = (0 until steps.length()).any {
                steps.optJSONObject(it)?.optString("type") == "function_call"
            }
            val functionResults = JSONArray()
            var functionCalls = 0

            for (index in 0 until steps.length()) {
                val step = steps.optJSONObject(index) ?: continue
                when (step.optString("type")) {
                    // Gemini thought summaries are intentionally not shown in the transcript.
                    // The UI displays an execution summary assembled from actual tools/results.
                    "thought" -> Unit

                    "url_context_call" -> onEvent(
                        AgenticEvent("url_context", "Reading web page", "Gemini URL Context")
                    )
                    "url_context_result" -> onEvent(
                        AgenticEvent("url_context_result", "Page content retrieved", step.toString().take(3000))
                    )
                    "code_execution_call" -> {
                        val code = step.optJSONObject("arguments")?.optString("code").orEmpty()
                        onEvent(AgenticEvent("hosted_python", "Running hosted Python", "Google-hosted execution", code))
                    }
                    "code_execution_result" -> onEvent(
                        AgenticEvent("hosted_python_result", "Hosted Python output", step.opt("result")?.toString().orEmpty().take(12000))
                    )
                    "function_call" -> {
                        val name = step.optString("name")
                        val callId = step.optString("id")
                        val arguments = step.optJSONObject("arguments") ?: JSONObject()
                        val displayName = name.replace('_', ' ').replaceFirstChar { it.uppercase() }
                        if (name == "run_local_python") {
                            onEvent(AgenticEvent("python_start", "Running local Python", "Bundled CPython · on this device", arguments.optString("code")))
                        } else {
                            onEvent(AgenticEvent("tool_start", displayName, arguments.toString()))
                        }

                        val result = try {
                            executor.execute(name, arguments)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            "Tool failed: " + (error.message ?: "unknown error")
                        }

                        if (name == "run_local_python") {
                            onEvent(AgenticEvent("python_result", "Local Python output", result.take(16000)))
                        } else {
                            onEvent(AgenticEvent("tool_result", displayName + " result", result.take(12000)))
                        }

                        functionResults.put(
                            JSONObject()
                                .put("type", "function_result")
                                .put("name", name)
                                .put("call_id", callId)
                                .put(
                                    "result",
                                    JSONArray().put(JSONObject().put("type", "text").put("text", result.take(24000)))
                                )
                        )
                        functionCalls++
                    }
                    "model_output" -> {
                        val content = step.optJSONArray("content") ?: JSONArray()
                        for (partIndex in 0 until content.length()) {
                            val part = content.optJSONObject(partIndex) ?: continue
                            when (part.optString("type")) {
                                "text" -> {
                                    val text = part.optString("text")
                                    if (text.isNotBlank()) {
                                        if (hasFunctionCalls && transcript.isEmpty()) {
                                            onEvent(AgenticEvent("plan", "Plan", text.take(2400)))
                                        } else {
                                            if (transcript.isNotEmpty()) transcript.append("\n")
                                            transcript.append(text)
                                        }
                                    }
                                }
                                "image" -> {
                                    val data = part.optString("data")
                                    val uri = part.optString("uri")
                                    val payload = data.ifBlank { uri }
                                    if (payload.isNotBlank()) onEvent(
                                        AgenticEvent("generated_image", "Generated image", part.optString("mime_type", "image/png"), payload = payload)
                                    )
                                }
                                "audio" -> {
                                    val data = part.optString("data")
                                    if (data.isNotBlank()) onEvent(
                                        AgenticEvent("generated_audio", "Generated audio", part.optString("mime_type", "audio/mpeg"), payload = data)
                                    )
                                }
                                "video" -> {
                                    val data = part.optString("data")
                                    val uri = part.optString("uri")
                                    if (data.isNotBlank() || uri.isNotBlank()) onEvent(
                                        AgenticEvent("generated_video", "Generated video", part.optString("mime_type", "video/mp4"), payload = if (data.isNotBlank()) data else uri)
                                    )
                                }
                                "document" -> {
                                    val data = part.optString("data")
                                    if (data.isNotBlank()) onEvent(
                                        AgenticEvent("generated_document", "Generated document", part.optString("mime_type", "application/pdf"), payload = data)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (functionCalls == 0) {
                val outputText = root.optString("output_text")
                if (transcript.isEmpty() && outputText.isNotBlank()) transcript.append(outputText)
                return if (transcript.isNotBlank()) {
                    AgenticResult.Success(transcript.toString(), model, interactionId)
                } else {
                    AgenticResult.Failed("Gemini completed the interaction without a text response.")
                }
            }

            if (round == MAX_TOOL_ROUNDS - 1) {
                return AgenticResult.Failed("The agent reached the 12-round tool-call safety limit. Ask it to continue.")
            }
            if (interactionId.isNullOrBlank()) {
                return AgenticResult.Failed("Gemini returned tool calls without an interaction ID; execution stopped safely.")
            }
            input = functionResults
        }

        return AgenticResult.Failed("The agent stopped before producing a final answer.")
    }

    private fun makeInput(prompt: String, media: List<AgentMediaInput>): JSONArray {
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            media.forEach { put(it.toContentBlock()) }
        }
    }

    private fun apiError(code: Int, raw: String): String {
        val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
        return detail?.takeIf { it.isNotBlank() }?.take(280) ?: when (code) {
            400 -> "Gemini rejected the request. Check selected model capabilities and attached media types."
            401, 403 -> "Gemini rejected the API key or API access is disabled."
            404 -> "This model or Interactions API is unavailable for this key. Select a supported model."
            413 -> "The attached media exceeds Gemini's request-size limit. Try fewer or smaller files."
            429 -> "Gemini rate limit or quota reached."
            else -> "Gemini Interactions API failed (HTTP " + code + ")."
        }
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 12
        private const val INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val API_REVISION = "2026-05-20"
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
