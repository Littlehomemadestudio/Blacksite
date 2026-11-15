package studio.oracle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeminiUploadedFile(val name: String, val uri: String, val mimeType: String)

/** Resumable upload for videos and other media too large for inline content blocks. */
class GeminiFileUploader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    suspend fun upload(
        apiKey: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray
    ): GeminiUploadedFile {
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }
        require(bytes.isNotEmpty()) { "Cannot upload an empty media file." }
        require(bytes.size <= MAX_UPLOAD_BYTES) { "This media file exceeds the 30 MB per-file limit." }

        val metadata = JSONObject().put("file", JSONObject().put("display_name", displayName))
        val startRequest = Request.Builder()
            .url(UPLOAD_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .header("Content-Type", "application/json")
            .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val uploadUrl = http.newCall(startRequest).await().use { response ->
            if (!response.isSuccessful) throw IOException("Gemini file upload setup failed (HTTP " + response.code + ").")
            response.header("x-goog-upload-url")
                ?: throw IOException("Gemini did not return a resumable upload URL.")
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        val file = http.newCall(uploadRequest).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gemini media upload failed (HTTP " + response.code + "). " + raw.take(240))
            val root = JSONObject(raw)
            val file = root.optJSONObject("file") ?: root
            val name = file.optString("name")
            val uri = file.optString("uri")
            if (name.isBlank() || uri.isBlank()) throw IOException("Gemini finalized the upload but returned no file URI.")
            GeminiUploadedFile(name, uri, file.optString("mimeType").ifBlank { mimeType })
        }

        if (mimeType.startsWith("video/")) awaitVideoProcessing(apiKey, file)
        return file
    }

    private suspend fun awaitVideoProcessing(apiKey: String, file: GeminiUploadedFile) {
        repeat(40) {
            delay(1500)
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/" + file.name)
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            val state = http.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("Could not check uploaded video processing state (HTTP " + response.code + ").")
                JSONObject(response.body?.string().orEmpty()).optString("state")
            }
            when (state.uppercase()) {
                "ACTIVE" -> return
                "FAILED" -> throw IOException("Google could not process video '" + file.name + "'.")
            }
        }
        throw IOException("Video processing is still pending. Try again in a moment.")
    }

    companion object {
        private const val UPLOAD_ENDPOINT = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val MAX_UPLOAD_BYTES = 30 * 1024 * 1024
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
