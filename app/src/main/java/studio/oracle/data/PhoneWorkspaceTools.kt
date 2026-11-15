package studio.oracle.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class CreatedAgentFile(val uri: String, val name: String, val mimeType: String)

/** SAF-scoped phone tools, web search and local Python execution. */
class PhoneWorkspaceTools(
    private val context: Context,
    private val grantedTree: Uri?,
    private val tavilyKey: String?,
    private val authorize: suspend (action: String, permissionGroup: String) -> Boolean,
    private val python: suspend (String) -> String,
    private val onFileCreated: (CreatedAgentFile) -> Unit = {}
) : AgenticToolExecutor {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .build()

    override fun declarations(): JSONArray = JSONArray().apply {
        put(function("web_search", "Search the live web using Tavily for current facts, documentation and research. Return concise findings with original source URLs.", schema("query" to property("The exact search query to run.")), arrayOf("query")))
        put(function("list_phone_files", "List files and folders beneath the folder the user explicitly granted to ORACLE.", schema("path" to property("Relative folder path. Empty means the granted root.")), arrayOf("path")))
        put(function("read_phone_file", "Read a UTF-8 text file under the granted phone folder. Contents are sent to Gemini for this task.", schema("path" to property("Relative file path under the granted folder.")), arrayOf("path")))
        put(function("create_phone_file", "Create a text, Markdown, JSON, HTML or CSV file under the granted phone folder. ORACLE displays it as an editable in-chat artifact.", schema("path" to property("Relative path including filename."), "content" to property("Complete UTF-8 contents."), "mime_type" to property("Optional MIME type, e.g. text/markdown or application/json.")), arrayOf("path", "content")))
        put(function("append_phone_file", "Append text to an existing UTF-8 file under the granted phone folder.", schema("path" to property("Relative file path."), "content" to property("Text to append.")), arrayOf("path", "content")))
        put(function("run_local_python", "Run Python 3 on-device in ORACLE's bundled CPython runtime. Network access and normal Python standard-library imports are enabled. Use it for calculations, data processing, code and downloads.", schema("code" to property("Python 3 source code to execute.")), arrayOf("code")))
    }

    override suspend fun execute(name: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        when (name) {
            "web_search" -> search(arguments.optString("query"))
            "list_phone_files" -> {
                val root = rootOrNull() ?: return@withContext "Phone folder access is not enabled. Choose a folder in ORACLE."
                val folder = resolve(root, arguments.optString("path")) ?: return@withContext "Folder not found under the granted directory."
                folder.listFiles().take(300).joinToString("\n") {
                    (if (it.isDirectory) "DIR  " else "FILE ") + it.name.orEmpty()
                }.ifBlank { "This folder is empty." }
            }
            "read_phone_file" -> {
                val path = safePath(arguments.optString("path")) ?: return@withContext "Unsafe file path rejected."
                val root = rootOrNull() ?: return@withContext "Phone folder access is not enabled."
                val file = resolve(root, path.joinToString("/")) ?: return@withContext "File not found under the granted directory."
                if (!file.isFile) return@withContext "The requested path is not a file."
                if (file.length() > MAX_PHONE_READ_BYTES) return@withContext "The file is larger than ORACLE's 10 MB text-read limit."
                if (!authorize("Read '" + path.joinToString("/") + "' and send its contents to Gemini for this task?", "phone_files")) return@withContext "The user declined file access."
                context.contentResolver.openInputStream(file.uri)?.use {
                    BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
                } ?: "Android could not open the selected file."
            }
            "create_phone_file" -> {
                val path = safePath(arguments.optString("path")) ?: return@withContext "Unsafe file path rejected."
                if (path.isEmpty()) return@withContext "A filename is required."
                val content = arguments.optString("content")
                if (content.toByteArray(StandardCharsets.UTF_8).size > MAX_PHONE_WRITE_BYTES) return@withContext "File exceeds the 10 MB text-write limit."
                if (!authorize("Create '" + path.joinToString("/") + "' in the folder granted to ORACLE?", "phone_files")) return@withContext "The user declined file creation."
                val root = rootOrNull() ?: return@withContext "Phone folder access is not enabled."
                val parent = ensureParent(root, path.dropLast(1)) ?: return@withContext "Could not create the requested parent folder."
                if (parent.findFile(path.last()) != null) return@withContext "A file with that name already exists. Choose a different name."
                val mime = arguments.optString("mime_type").takeIf { it.contains("/") } ?: mimeForName(path.last())
                val file = parent.createFile(mime, path.last()) ?: return@withContext "Android could not create the file."
                context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                    it.write(content.toByteArray(StandardCharsets.UTF_8))
                } ?: return@withContext "Android could not write the file."
                onFileCreated(CreatedAgentFile(file.uri.toString(), file.name ?: path.last(), mime))
                "Created " + path.joinToString("/") + " successfully. The file is now available as an editable artifact in the conversation."
            }
            "append_phone_file" -> {
                val path = safePath(arguments.optString("path")) ?: return@withContext "Unsafe file path rejected."
                if (path.isEmpty()) return@withContext "A filename is required."
                val content = arguments.optString("content")
                if (content.toByteArray(StandardCharsets.UTF_8).size > MAX_PHONE_WRITE_BYTES) return@withContext "Appended text exceeds the 10 MB limit."
                if (!authorize("Append " + content.length + " characters to '" + path.joinToString("/") + "'?", "phone_files")) return@withContext "The user declined this file modification."
                val root = rootOrNull() ?: return@withContext "Phone folder access is not enabled."
                val file = resolve(root, path.joinToString("/")) ?: return@withContext "File not found under the granted directory."
                if (!file.isFile) return@withContext "The requested path is not a file."
                context.contentResolver.openOutputStream(file.uri, "wa")?.use {
                    it.write(content.toByteArray(StandardCharsets.UTF_8))
                } ?: return@withContext "Android could not append to the file."
                onFileCreated(CreatedAgentFile(file.uri.toString(), file.name.orEmpty(), file.type ?: "text/plain"))
                "Appended " + content.length + " characters to " + path.last() + " successfully."
            }
            "run_local_python" -> {
                val code = arguments.optString("code")
                if (code.isBlank()) return@withContext "Python source is empty."
                if (code.length > MAX_PYTHON_SOURCE_CHARS) return@withContext "Python source exceeds the 256 KB request limit."
                if (!authorize("Execute this Python program on this device? Network access and normal Python imports are enabled.", "python")) return@withContext "The user declined Python execution."
                python(code)
            }
            else -> "Unknown or disabled tool: " + name
        }
    }

    private suspend fun search(query: String): String {
        if (query.isBlank()) return "Search query is empty."
        val key = tavilyKey?.takeIf { it.isNotBlank() } ?: return "Web search is not configured. Ask the user to add a Tavily API key in ORACLE Settings."
        val body = JSONObject().put("query", query).put("search_depth", "advanced").put("max_results", 8).put("include_answer", true).put("include_raw_content", false)
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer " + key)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(raw).optString("detail") }.getOrNull().orEmpty()
                    if (response.code == 401 || response.code == 403) "Tavily rejected the saved search key. Reconnect it in ORACLE Settings."
                    else "Tavily search failed (HTTP " + response.code + "). " + message.take(240)
                } else {
                    val root = JSONObject(raw)
                    buildString {
                        val answer = root.optString("answer")
                        if (answer.isNotBlank()) append("Search overview: ").append(answer).append("\n\n")
                        val results = root.optJSONArray("results") ?: JSONArray()
                        for (i in 0 until minOf(results.length(), 8)) {
                            val item = results.optJSONObject(i) ?: continue
                            append(i + 1).append(". ").append(item.optString("title")).append("\n")
                            append(item.optString("content").take(1100)).append("\n")
                            append(item.optString("url")).append("\n\n")
                        }
                        if (isEmpty()) append("No search results were returned.")
                    }
                }
            }
        } catch (error: Exception) {
            "Tavily search could not complete: " + (error.message ?: "network error")
        }
    }

    private fun rootOrNull(): DocumentFile? {
        val tree = grantedTree ?: return null
        if (context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) return null
        return DocumentFile.fromTreeUri(context, tree)
    }

    private fun resolve(root: DocumentFile, raw: String): DocumentFile? {
        if (raw.isBlank()) return root
        val segments = safePath(raw) ?: return null
        var current = root
        for (segment in segments) current = current.findFile(segment) ?: return null
        return current
    }

    private fun ensureParent(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    private fun safePath(raw: String): List<String>? {
        if (raw.length > 768 || raw.startsWith("/") || raw.contains("\\")) return null
        val parts = raw.split("/").filter { it.isNotBlank() }
        if (parts.any { it == "." || it == ".." || it.length > 160 || it.any(Char::isISOControl) }) return null
        return parts
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        else -> "text/plain"
    }

    private fun property(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun schema(vararg properties: Pair<String, JSONObject>) = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { properties.forEach { put(it.first, it.second) } })
    }
    private fun function(name: String, description: String, parameters: JSONObject, required: Array<String>) =
        JSONObject().put("type", "function").put("name", name).put("description", description)
            .put("parameters", parameters.put("required", JSONArray(required.toList())))

    companion object {
        private const val MAX_PHONE_READ_BYTES = 10L * 1024L * 1024L
        private const val MAX_PHONE_WRITE_BYTES = 10 * 1024 * 1024
        private const val MAX_PYTHON_SOURCE_CHARS = 256 * 1024
    }
}
