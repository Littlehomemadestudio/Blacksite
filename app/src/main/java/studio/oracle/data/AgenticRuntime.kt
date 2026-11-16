package studio.oracle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val imageGeneration: Boolean = true,
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
 * Per-run guardrails prevent a model/tool loop from consuming unbounded requests or local actions.
 * A batch is reserved atomically so a response that exceeds the remaining budget executes no
 * additional local function calls.
 */
internal class AgenticExecutionBudget(
    private val maxRounds: Int = MAX_ROUNDS,
    private val maxToolCalls: Int = MAX_TOOL_CALLS
) {
    init {
        require(maxRounds > 0) { "maxRounds must be positive." }
        require(maxToolCalls > 0) { "maxToolCalls must be positive." }
    }

    var roundsUsed: Int = 0
        private set

    var toolCallsUsed: Int = 0
        private set

    val canStartRound: Boolean
        get() = roundsUsed < maxRounds

    fun beginRound(): Boolean {
        if (!canStartRound) return false
        roundsUsed += 1
        return true
    }

    fun reserveToolCalls(count: Int): Boolean {
        if (count < 0 || count > maxToolCalls || toolCallsUsed > maxToolCalls - count) {
            return false
        }
        toolCallsUsed += count
        return true
    }

    companion object {
        const val MAX_ROUNDS = 16
        const val MAX_TOOL_CALLS = 32
    }
}

/**
 * Session grants are capability-scoped. Approving Python in a conversation must not
 * implicitly authorize file reads, file writes, web queries, or image generation.
 */
internal object AgenticPermissionPolicy {
    fun sessionGrantKey(sessionId: String, permissionGroup: String): String {
        require(sessionId.isNotBlank()) { "An active conversation is required for a session grant." }
        require(permissionGroup.isNotBlank()) { "A permission group is required for a session grant." }
        return sessionId.trim() + "\u001f" + permissionGroup.trim()
    }
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
        val imageOnlyModel = model.contains("flash-image", ignoreCase = true)
        if (imageOnlyModel && media.any { it.mimeType.startsWith("audio/") }) {
            return AgenticResult.Failed("Gemini Flash Image does not accept audio input. Switch to the main Gemini agent model for audio.")
        }
        val tools = JSONArray().apply {
            if (!imageOnlyModel && cfg.hostedPython) put(JSONObject().put("type", "code_execution"))
            if (!imageOnlyModel && cfg.urlContext) put(JSONObject().put("type", "url_context"))
            if (!imageOnlyModel && cfg.localTools) {
                val declared = executor.declarations()
                for (i in 0 until declared.length()) put(declared.getJSONObject(i))
            }
        }

        var interactionId: String? = null
        var input: Any = makeInput(prompt, media)
        val transcript = StringBuilder()
        var planSummaryEmitted = false
        val executionBudget = AgenticExecutionBudget()

        while (executionBudget.beginRound()) {
            currentCoroutineContext().ensureActive()
            val body = JSONObject()
                .put("model", model)
                .put("input", input)
                .put(
                    "system_instruction",
                    "You are ORACLE, a capable multimodal AI operator inside an Android workspace. " +
                        "Use tools when they materially help; execute tasks instead of only explaining how. " +
                        "For multi-step work, plan briefly, perform tool calls, inspect their actual results, and continue until complete. " +
                        "Never claim an action succeeded unless a tool result confirms it. " +
                        "Phone file tools are limited to the folder the user granted. " +
                        "The app obtains approval before sending web-search queries to Tavily, reading or modifying phone files, running local Python, or generating images. " +
                        "Session approvals are scoped to one capability group and do not authorize unrelated tools. " +
                        "You may use the local Python tool for normal Python work, including standard-library networking and file downloads. " +
                        "Keep progress updates brief. Do not output private chain-of-thought; provide a concise user-facing summary and final deliverable."
                )
                .put(
                    "generation_config",
                    JSONObject()
                        .put("thinking_level", cfg.thinkingLevel)
                        .put("thinking_summaries", cfg.thinkingSummaries)
                        .put("max_output_tokens", cfg.maxOutputTokens)
                )
            if (tools.length() > 0) body.put("tools", tools)
            if (!imageOnlyModel) body.getJSONObject("generation_config").put("tool_choice", cfg.toolChoice)
            if (imageOnlyModel) {
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

            // Retry transient transport/API failures without replaying completed local tools.
            // Tool execution only starts after a complete model response has been received.
            var responseRoot: JSONObject? = null
            var lastFailure: String? = null
            for (attempt in 0..5) {
                val response = try {
                    http.newCall(request).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (network: IOException) {
                    lastFailure = network.message ?: "Connection interrupted."
                    if (attempt < 5) {
                        onEvent(AgenticEvent("network_retry", "Reconnecting to Gemini", "Retry ${attempt + 1} of 5"))
                        delay((1000L shl attempt).coerceAtMost(16000L))
                        continue
                    }
                    return AgenticResult.Failed(
                        "Connection interrupted after 6 attempts. Completed tool results are retained in this turn; retry to continue. ${lastFailure}"
                    )
                } catch (error: Exception) {
                    return AgenticResult.Failed(error.message?.take(240) ?: "Could not contact Gemini.")
                }

                val code = response.code
                val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000L)
                val raw = try {
                    response.body?.string().orEmpty()
                } catch (network: IOException) {
                    response.close()
                    lastFailure = network.message ?: "Response stream interrupted."
                    if (attempt < 5) {
                        onEvent(AgenticEvent("network_retry", "Restoring response stream", "Retry ${attempt + 1} of 5"))
                        delay((1000L shl attempt).coerceAtMost(16000L))
                        continue
                    }
                    return AgenticResult.Failed(
                        "Gemini's response stream disconnected after 6 attempts. Completed tool results are retained in this turn. ${lastFailure}"
                    )
                }
                response.close()
                if (code in 200..299) {
                    responseRoot = try {
                        JSONObject(raw)
                    } catch (error: Exception) {
                        return AgenticResult.Failed("Gemini returned an unreadable response: ${error.message?.take(180)}")
                    }
                    break
                }
                if (code in RETRYABLE_HTTP_CODES && attempt < 5) {
                    onEvent(AgenticEvent("network_retry", "Retrying Gemini request", "HTTP ${code} · attempt ${attempt + 1} of 5"))
                    delay((retryAfterMs ?: (1000L shl attempt)).coerceIn(1000L, 30000L))
                    continue
                }
                return AgenticResult.Failed(apiError(code, raw))
            }
            val root = responseRoot ?: return AgenticResult.Failed(
                "Gemini did not return a response. ${lastFailure ?: "Check your connection and retry."}"
            )

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
            val hasToolCalls = (0 until steps.length()).any { index ->
                steps.optJSONObject(index)?.optString("type") in setOf(
                    "function_call",
                    "code_execution_call",
                    "url_context_call"
                )
            }
            val toolStepCount = (0 until steps.length()).count { index ->
                steps.optJSONObject(index)?.optString("type") in setOf(
                    "function_call",
                    "code_execution_call",
                    "url_context_call"
                )
            }
            if (hasToolCalls && !executionBudget.canStartRound) {
                val message = "ORACLE reached the safety limit of ${AgenticExecutionBudget.MAX_ROUNDS} model rounds. " +
                    "No additional local tools were executed; start a new turn to continue."
                onEvent(AgenticEvent("execution_limit", "Agent run limit reached", message))
                return AgenticResult.Failed(message)
            }
            if (!executionBudget.reserveToolCalls(toolStepCount)) {
                val message = "ORACLE reached the safety limit of ${AgenticExecutionBudget.MAX_TOOL_CALLS} tool calls. " +
                    "The remaining local actions were not executed; start a new turn to continue."
                onEvent(AgenticEvent("execution_limit", "Tool budget reached", message))
                return AgenticResult.Failed(message)
            }
            val functionResults = JSONArray()
            var functionCalls = 0

            for (index in 0 until steps.length()) {
                currentCoroutineContext().ensureActive()
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
                                        if (hasToolCalls && transcript.isEmpty()) {
                                            if (!planSummaryEmitted) {
                                                onEvent(AgenticEvent("plan", "Plan", text.take(2400)))
                                                planSummaryEmitted = true
                                            }
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

            if (interactionId.isNullOrBlank()) {
                return AgenticResult.Failed("Gemini returned tool calls without an interaction ID; execution stopped safely.")
            }
            input = functionResults
        }

        val message = "ORACLE reached the safety limit of ${AgenticExecutionBudget.MAX_ROUNDS} model rounds before a final answer. " +
            "Start a new turn to continue with a fresh execution budget."
        onEvent(AgenticEvent("execution_limit", "Agent run limit reached", message))
        return AgenticResult.Failed(message)
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
        private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
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
