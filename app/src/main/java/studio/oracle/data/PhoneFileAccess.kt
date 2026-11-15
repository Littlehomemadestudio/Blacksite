package studio.oracle.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * SAF-scoped phone-file access. The model never receives raw filesystem paths:
 * all operations are relative to the directory explicitly granted by the user.
 */
class PhoneFileAccess(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("oracle_phone_files", Context.MODE_PRIVATE)

    fun currentTreeUri(): Uri? =
        preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun saveTreeUri(uri: Uri) {
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clearTreeUri() {
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    fun listFiles(relativePath: String = ""): JSONObject {
        val directory = resolve(relativePath)
        if (!directory.isDirectory) throw IOException("The requested path is not a folder.")
        val entries = JSONArray()
        directory.listFiles()
            .sortedBy { it.name?.lowercase(Locale.ROOT).orEmpty() }
            .take(MAX_LIST_ITEMS)
            .forEach { item ->
                entries.put(
                    JSONObject()
                        .put("name", item.name.orEmpty())
                        .put("path", joinPath(relativePath, item.name.orEmpty()))
                        .put("type", if (item.isDirectory) "folder" else "file")
                        .put("mimeType", item.type ?: JSONObject.NULL)
                        .put("sizeBytes", if (item.isFile) item.length() else JSONObject.NULL)
                )
            }
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath)
            .put("truncated", directory.listFiles().size > MAX_LIST_ITEMS)
            .put("items", entries)
    }

    fun readFile(relativePath: String): JSONObject {
        val file = resolve(relativePath)
        if (!file.isFile) throw IOException("The requested path is not a file.")
        if (file.length() > MAX_READ_BYTES) {
            throw IOException("This file is larger than the 1 MB agent-read limit.")
        }
        val input = appContext.contentResolver.openInputStream(file.uri)
            ?: throw IOException("Android could not open this file.")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_READ_BYTES) throw IOException("File exceeds the 1 MB agent-read limit.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.any { it == 0.toByte() }) {
            throw IOException("Binary files cannot be read as text.")
        }
        return JSONObject()
            .put("ok", true)
            .put("path", relativePath)
            .put("content", bytes.toString(Charsets.UTF_8))
    }

    fun createFile(name: String, content: String): JSONObject {
        val safeName = name.trim()
        if (safeName.isBlank() || safeName.length > 120 ||
            safeName == "." || safeName == ".." ||
            safeName.contains('/') || safeName.contains('\\') ||
            safeName.any { it.code < 32 }
        ) {
            throw IOException("Choose a simple filename without folder separators.")
        }
        if (content.length > MAX_WRITE_CHARS) {
            throw IOException("The file content exceeds the 1 MB write limit.")
        }
        val root = rootDirectory()
        if (root.findFile(safeName) != null) {
            throw IOException("A file with that name already exists. Choose a different name.")
        }
        val mimeType = when (safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "xml" -> "application/xml"
            "py" -> "text/x-python"
            "kt", "java", "txt", "log" -> "text/plain"
            else -> "application/octet-stream"
        }
        val created = root.createFile(mimeType, safeName)
            ?: throw IOException("The selected folder could not create a new file.")
        try {
            val output = appContext.contentResolver.openOutputStream(created.uri, "wt")
                ?: throw IOException("Android could not open the new file for writing.")
            output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (failure: Exception) {
            created.delete()
            throw failure
        }
        return JSONObject()
            .put("ok", true)
            .put("name", safeName)
            .put("uri", created.uri.toString())
            .put("charactersWritten", content.length)
    }

    private fun rootDirectory(): DocumentFile {
        val tree = currentTreeUri() ?: throw IOException("Grant a phone folder from ORACLE's tool panel first.")
        return DocumentFile.fromTreeUri(appContext, tree)
            ?: throw IOException("The granted phone folder is no longer available. Grant it again.")
    }

    private fun resolve(relativePath: String): DocumentFile {
        var current = rootDirectory()
        val normalized = relativePath.trim().trim('/')
        if (normalized.isBlank()) return current
        val segments = normalized.split('/')
        if (segments.size > MAX_PATH_DEPTH ||
            segments.any { it.isBlank() || it == "." || it == ".." || it.contains('\\') }
        ) {
            throw IOException("The requested relative path is invalid.")
        }
        segments.forEach { segment ->
            current = current.findFile(segment)
                ?: throw IOException("No file or folder exists at “$relativePath”.")
        }
        return current
    }

    private fun joinPath(parent: String, child: String): String =
        if (parent.isBlank()) child else "$parent/$child"

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
        const val MAX_READ_BYTES = 1_048_576
        const val MAX_WRITE_CHARS = 1_048_576
        const val MAX_LIST_ITEMS = 100
        const val MAX_PATH_DEPTH = 8
    }
}
