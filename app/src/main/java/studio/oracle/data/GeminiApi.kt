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
    data class Success(
        val text: String,
        val model: String,
        /** Includes the exact model/tool parts required to continue Gemini 3 tool-context circulation. */
        val persistedContent: String = text
    ) : GeminiReplyResult
    data class Failed(val message: String) : GeminiReplyResult
}

data class GeminiModel(
    val name: String,
    val displayName: String,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null
)

fun interface AgentToolExecutor {
    suspend fun execute(name: String, arguments: JSONObject): JSONObject
}

sealed interface GeminiModelsResult {
    data class Success(val models: List<GeminiModel>) : GeminiModelsResult
    data class Failed(val message: String) : GeminiModelsResult
}

/**
 * Gemini GenerateContent client with built-in Google tools and a bounded client-side
 * function-calling loop. Built-in tools execute on Google's servers. Custom tools execute
 * only inside the app after explicit capability/permission checks.
 */
class GeminiApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
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
                if (response.isSuccessful) {
                    GeminiConnectionResult.Connected("Gemini API key verified.")
                } else {
                    GeminiConnectionResult.Failed(connectionError(response.code))
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

    /** Fetches model names and the actual token limits exposed to this API key. */
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
                                        inputTokenLimit = item.optInt("inputTokenLimit").takeIf { it > 0 },
                                        outputTokenLimit = item.optInt("outputTokenLimit").takeIf { it > 0 }
                                    )
                                )
                            }
                        }
                    }.sortedWith(
                        compareBy<GeminiModel>(
                            { !it.name.contains("flash", ignoreCase = true) },
                            { it.displayName }
                        )
                    )
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

    suspend fun generate(
        apiKey: String,
        prompt: String,
        history: List<Pair<String, String>>,
        model: String = ModelPreferences.DEFAULT_MODEL,
        settings: AgentToolSettings = AgentToolSettings(),
        toolExecutor: AgentToolExecutor = AgentToolExecutor { name, _ ->
            JSONObject().put("ok", false).put("error", "Tool is not available: $name")
        }
    ): GeminiReplyResult {
        if (apiKey.isBlank()) return GeminiReplyResult.Failed("Connect a Gemini API key in Settings first.")
        if (prompt.isBlank()) return GeminiReplyResult.Failed("Write an instruction before running ORACLE.")
        if (!model.matches(Regex("[A-Za-z0-9._-]+"))) {
            return GeminiReplyResult.Failed("The selected Gemini model identifier is invalid.")
        }

        val contents = JSONArray()
        history.takeLast(settings.contextMessages.coerceIn(1, 48)).forEach { (role, value) ->
            if (role == "assistant" && value.startsWith(PERSISTED_PREFIX)) {
                val restored = runCatching {
                    JSONObject(value.removePrefix(PERSISTED_PREFIX)).optJSONArray("turns")
                }.getOrNull()
                if (restored != null) {
                    for (index in 0 until restored.length()) {
                        restored.optJSONObject(index)?.let(contents::put)
                    }
                } else {
                    contents.put(content(role, value))
                }
            } else {
                contents.put(content(role, value))
            }
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )

        val modelSupportsToolCombination = model.startsWith("gemini-3", ignoreCase = true)
        val customToolsRequested = settings.localPython || settings.phoneFiles
        val canCombineCustomAndBuiltIn = modelSupportsToolCombination
        val includeCustomTools = customToolsRequested &&
            (!settings.googleSearch && !settings.cloudCodeExecution || canCombineCustomAndBuiltIn)

        val tools = JSONArray()
        if (settings.googleSearch) tools.put(JSONObject().put("googleSearch", JSONObject()))
        if (settings.cloudCodeExecution) tools.put(JSONObject().put("codeExecution", JSONObject()))
        if (includeCustomTools) tools.put(functionTool(settings))

        val systemText = buildString {
            append(
                "You are ORACLE, an agentic AI operator inside an Android workspace. " +
                    "You may plan, use the tools explicitly supplied in this request, inspect their results, " +
                    "and continue until the task is complete. Do not merely describe a tool action when a tool is available; " +
                    "call it, inspect its result, and report what actually happened. Never invent tool results. "
            )
            if (settings.googleSearch) {
                append("Use Google Search for current facts, recent information, and verifiable web research. ")
                append("Cite the returned web sources in your response. ")
            }
            if (settings.cloudCodeExecution) {
                append("You may use Google's managed Python code execution tool for calculations and data analysis. ")
            }
            if (settings.localPython && includeCustomTools) {
                append("You may use run_local_python for on-device Python calculations. This is a restricted local runtime, not a shell. ")
            }
            if (settings.phoneFiles && includeCustomTools) {
                append(
                    "Phone file tools operate only within the one folder the user granted. " +
                        "Use list_phone_files before assuming a path exists. " +
                        "Read files only when relevant. A create_phone_file call requires a visible user approval " +
                        "before the app writes anything. Never try path traversal or claim access outside that folder. "
                )
            }
            if (customToolsRequested && !includeCustomTools) {
                append(
                    "This selected model cannot combine Google's built-in tools and ORACLE's local custom tools in one call. " +
                        "Only the enabled Google tools are currently executable. Do not claim you accessed the phone or local Python. " +
                        "The user can turn off Google tools or choose a Gemini 3 model to enable combined agent tools. "
                )
            }
            append(
                "For multi-step work, decide which tools are useful, call them in a sensible order, " +
                    "verify results, and give a concise completion report. Ask before any destructive or external action."
            )
        }

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
            .put("contents", contents)
            .put(
                "generationConfig",
                generationConfig(model, settings)
            )
        if (tools.length() > 0) body.put("tools", tools)

        val persistedTurns = JSONArray()
        var finalDisplay = ""
        var finalCandidateContent: JSONObject? = null
        val localPythonTraces = JSONArray()

        return try {
            var rounds = 0
            while (rounds < MAX_TOOL_ROUNDS) {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val responsePayload = http.newCall(request).await().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return GeminiReplyResult.Failed(apiError(response.code, raw))
                    JSONObject(raw)
                }

                val candidate = responsePayload.optJSONArray("candidates")?.optJSONObject(0)
                    ?: return GeminiReplyResult.Failed(
                        responsePayload.optJSONObject("promptFeedback")?.optString("blockReason")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "Gemini blocked this request: $it" }
                            ?: "Gemini returned no candidate. Try a different instruction."
                    )
                val candidateContent = candidate.optJSONObject("content")
                    ?: return GeminiReplyResult.Failed("Gemini returned an empty response.")
                val parts = candidateContent.optJSONArray("parts") ?: JSONArray()
                val calls = mutableListOf<Pair<JSONObject, JSONObject>>()
                val immediateText = StringBuilder()

                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    val partText = part.optString("text")
                    if (partText.isNotBlank()) {
                        if (immediateText.isNotEmpty()) immediateText.append("\n")
                        immediateText.append(partText)
                    }
                    val functionCall = part.optJSONObject("functionCall")
                    if (functionCall != null) calls += part to functionCall
                }

                val hasSearchGrounding = responsePayload.optJSONObject("groundingMetadata") != null ||
                    responsePayload.optJSONObject("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("groundingMetadata") != null

                if (calls.isEmpty()) {
                    finalCandidateContent = candidateContent
                    val rendered = renderCandidate(candidateContent, responsePayload)
                    finalDisplay = renderLocalPython(rendered.ifBlank { immediateText.toString() }, localPythonTraces)
                    persistedTurns.put(candidateContent)
                    break
                }

                persistedTurns.put(candidateContent)
                contents.put(candidateContent)
                val functionResponses = JSONArray()
                for ((part, call) in calls) {
                    val name = call.optString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    val result = try {
                        toolExecutor.execute(name, args)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        JSONObject()
                            .put("ok", false)
                            .put("error", failure.message?.take(500) ?: "The tool failed.")
                    }
                    val functionResponse = JSONObject()
                        .put("name", name)
                        .put("response", result)
                    call.optString("id").takeIf { it.isNotBlank() }?.let {
                        functionResponse.put("id", it)
                    }
                    if (name == "run_local_python") {
                        localPythonTraces.put(
                            JSONObject()
                                .put("code", args.optString("code"))
                                .put("result", result)
                        )
                    }
                    functionResponses.put(JSONObject().put("functionResponse", functionResponse))
                }
                val responseContent = JSONObject()
                    .put("role", "user")
                    .put("parts", functionResponses)
                contents.put(responseContent)
                persistedTurns.put(responseContent)
                rounds++
            }

            if (finalCandidateContent == null) {
                GeminiReplyResult.Failed("ORACLE reached its $MAX_TOOL_ROUNDS-step tool limit. Ask it to continue from the recorded results.")
            } else if (finalDisplay.isBlank()) {
                GeminiReplyResult.Failed("Gemini returned no text. Try a different instruction.")
            } else {
                val envelope = JSONObject()
                    .put("displayText", finalDisplay)
                    .put("turns", persistedTurns)
                    .put("model", model)
                    .put("toolRounds", rounds)
                GeminiReplyResult.Success(
                    text = finalDisplay,
                    model = model,
                    persistedContent = PERSISTED_PREFIX + envelope.toString()
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GeminiReplyResult.Failed("Network error while contacting Gemini. Check your connection and retry.")
        } catch (failure: Exception) {
            GeminiReplyResult.Failed(failure.message?.take(300) ?: "Could not read Gemini's response. Please retry.")
        }
    }

    private fun content(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", if (role == "assistant") "model" else "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun generationConfig(model: String, settings: AgentToolSettings): JSONObject {
        val config = JSONObject()
            .put("maxOutputTokens", settings.maxOutputTokens.coerceIn(1024, 32768))
        if (model.startsWith("gemini-3", ignoreCase = true)) {
            config.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", settings.thinkingLevel.uppercase())
            )
        } else if (model.startsWith("gemini-2.5", ignoreCase = true)) {
            val budget = when (settings.thinkingLevel) {
                "low" -> 1024
                "medium" -> 8192
                else -> 24576
            }
            config.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
            config.put("temperature", 0.45)
        } else {
            config.put("temperature", 0.45)
        }
        return config
    }

    private fun functionTool(settings: AgentToolSettings): JSONObject {
        val declarations = JSONArray()
        if (settings.localPython) {
            declarations.put(
                JSONObject()
                    .put("name", "run_local_python")
                    .put("description", "Write and execute Python code in ORACLE's restricted on-device Python runtime. Use for calculations, transformations, and data analysis. No shell, network, or general filesystem access.")
                    .put("parameters", objectSchema(
                        properties = JSONObject().put(
                            "code",
                            JSONObject().put("type", "STRING").put("description", "Complete Python 3 source code to run.")
                        ),
                        required = JSONArray().put("code")
                    ))
            )
        }
        if (settings.phoneFiles) {
            declarations.put(
                JSONObject()
                    .put("name", "list_phone_files")
                    .put("description", "List files and folders inside the one user-granted phone folder. Paths are relative to that folder.")
                    .put("parameters", objectSchema(
                        properties = JSONObject().put(
                            "path",
                            JSONObject().put("type", "STRING").put("description", "Optional relative folder path. Use an empty string for the granted root.")
                        ),
                        required = JSONArray()
                    ))
            )
            declarations.put(
                JSONObject()
                    .put("name", "read_phone_file")
                    .put("description", "Read a UTF-8 text file inside the user-granted phone folder. Maximum size is 1 MB.")
                    .put("parameters", objectSchema(
                        properties = JSONObject().put(
                            "path",
                            JSONObject().put("type", "STRING").put("description", "Relative file path inside the granted folder.")
                        ),
                        required = JSONArray().put("path")
                    ))
            )
            declarations.put(
                JSONObject()
                    .put("name", "create_phone_file")
                    .put("description", "Request creation of a new file at the root of the user-granted folder. The Android app will ask the user to approve before writing.")
                    .put("parameters", objectSchema(
                        properties = JSONObject()
                            .put("name", JSONObject().put("type", "STRING").put("description", "New filename including extension."))
                            .put("content", JSONObject().put("type", "STRING").put("description", "Complete UTF-8 text to write.")),
                        required = JSONArray().put("name").put("content")
                    ))
            )
        }
        return JSONObject().put("functionDeclarations", declarations)
    }

    private fun objectSchema(properties: JSONObject, required: JSONArray): JSONObject =
        JSONObject()
            .put("type", "OBJECT")
            .put("properties", properties)
            .put("required", required)

    private fun renderLocalPython(base: String, traces: JSONArray): String {
        if (traces.length() == 0) return base
        return buildString {
            if (base.isNotBlank()) append(base).append("\\n\\n")
            for (index in 0 until traces.length()) {
                val trace = traces.optJSONObject(index) ?: continue
                val result = trace.optJSONObject("result") ?: JSONObject()
                append("**On-device Python · execution ").append(index + 1).append("**\\n")
                append("\\n```python\\n")
                    .append(trace.optString("code"))
                    .append("\\n```\\n")
                val stdout = result.optString("stdout")
                val stderr = result.optString("stderr")
                val error = result.optString("error")
                append("\\n```text\\n")
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) append(stderr)
                if (error.isNotBlank()) append("ERROR: ").append(error)
                if (stdout.isBlank() && stderr.isBlank() && error.isBlank()) append("(No output)")
                append("\\n```\\n")
            }
        }.trim()
    }

    private fun renderCandidate(content: JSONObject, response: JSONObject): String {
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val python = StringBuilder()
        val output = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val spoken = part.optString("text")
            if (spoken.isNotBlank()) {
                if (text.isNotEmpty()) text.append("\n\n")
                text.append(spoken)
            }
            part.optJSONObject("executableCode")?.optString("code")
                ?.takeIf { it.isNotBlank() }?.let { python.append(it) }
            part.optJSONObject("codeExecutionResult")?.optString("output")
                ?.takeIf { it.isNotBlank() }?.let { output.append(it) }
        }
        if (python.isNotEmpty() || output.isNotEmpty()) {
            text.append("\n\n**Python execution**\n")
            if (python.isNotEmpty()) text.append("\n```python\n").append(python).append("\n```\n")
            if (output.isNotEmpty()) text.append("\n```text\n").append(output).append("\n```")
        }
        val grounding = response.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("groundingMetadata")
            ?: response.optJSONObject("groundingMetadata")
        val chunks = grounding?.optJSONArray("groundingChunks")
        if (chunks != null && chunks.length() > 0) {
            val seen = linkedSetOf<String>()
            for (index in 0 until chunks.length()) {
                val web = chunks.optJSONObject(index)?.optJSONObject("web") ?: continue
                val uri = web.optString("uri")
                val title = web.optString("title").ifBlank { uri }
                if (uri.startsWith("https://") && seen.add(uri)) {
                    if (seen.size <= 8) text.append("\n- [").append(title).append("](").append(uri).append(")")
                }
            }
            if (seen.isNotEmpty()) text.insert(0, "**Web sources**\n")
        }
        return text.toString().trim()
    }

    private fun connectionError(code: Int): String = when (code) {
        401, 403 -> "The key was rejected. Check the key and its API access."
        429 -> "Gemini rate limit reached. Try again later."
        else -> "Gemini returned HTTP $code."
    }

    private fun apiError(code: Int, raw: String): String =
        when (code) {
            401, 403 -> "Gemini rejected the API key. Reconnect it in Settings."
            429 -> "Gemini rate limit or quota reached. Check usage and retry."
            else -> {
                val detail = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull()
                detail?.takeIf { it.isNotBlank() }?.take(260)
                    ?: "Gemini request failed (HTTP $code)."
            }
        }

    companion object {
        private const val MAX_TOOL_ROUNDS = 8
        private const val PERSISTED_PREFIX = "ORACLE_GEMINI_TURN:"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Returns the user-visible answer while keeping tool signatures private in Room. */
        fun displayText(stored: String): String {
            if (!stored.startsWith(PERSISTED_PREFIX)) return stored
            return runCatching {
                JSONObject(stored.removePrefix(PERSISTED_PREFIX)).optString("displayText")
            }.getOrDefault(stored)
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
