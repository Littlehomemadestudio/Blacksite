package studio.oracle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeminiGeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val description: String
)

/** Dedicated image-generation endpoint. It is intentionally tool-less because the image model does not support function calling. */
class GeminiImageGenerator(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
) {
    suspend fun generate(
        apiKey: String,
        prompt: String,
        aspectRatio: String = "16:9",
        imageSize: String = "1K"
    ): GeminiGeneratedImage = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }
        require(prompt.isNotBlank()) { "Image description cannot be blank." }
        require(aspectRatio in ASPECT_RATIOS) { "Unsupported image aspect ratio." }
        require(imageSize in IMAGE_SIZES) { "Unsupported image size." }

        val input = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
        val responseFormat = JSONArray()
            .put(JSONObject().put("type", "text"))
            .put(
                JSONObject()
                    .put("type", "image")
                    .put("mime_type", "image/jpeg")
                    .put("aspect_ratio", aspectRatio)
                    .put("image_size", imageSize)
                    .put("delivery", "inline")
            )
        val body = JSONObject()
            .put("model", MODEL)
            .put("input", input)
            .put("response_format", responseFormat)

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .header("x-goog-api-key", apiKey)
            .header("Api-Revision", API_REVISION)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            http.newCall(request).await().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    throw IOException(
                        message?.takeIf { it.isNotBlank() }?.take(260)
                            ?: "Gemini image generation failed (HTTP " + response.code + ")."
                    )
                }
                val root = JSONObject(raw)
                val steps = root.optJSONArray("steps") ?: JSONArray()
                var generated: GeminiGeneratedImage? = null
                var description = root.optString("output_text")
                for (i in 0 until steps.length()) {
                    val step = steps.optJSONObject(i) ?: continue
                    if (step.optString("type") != "model_output") continue
                    val blocks = step.optJSONArray("content") ?: continue
                    for (j in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(j) ?: continue
                        when (block.optString("type")) {
                            "text" -> if (description.isBlank()) description = block.optString("text")
                            "image" -> {
                                val data = block.optString("data")
                                if (data.isNotBlank()) {
                                    generated = GeminiGeneratedImage(
                                        bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT),
                                        mimeType = block.optString("mime_type").ifBlank { "image/jpeg" },
                                        description = description
                                    )
                                }
                            }
                        }
                    }
                }
                generated ?: throw IOException(
                    "Gemini returned no image. Check that your key can use " + MODEL + " and try a more specific prompt."
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    companion object {
        const val MODEL = "gemini-3.1-flash-image"
        private const val API_REVISION = "2026-05-20"
        private val ASPECT_RATIOS = setOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")
        private val IMAGE_SIZES = setOf("0.5K", "1K", "2K", "4K")
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
