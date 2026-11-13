package studio.oracle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface GeminiConnectionResult {
    data class Connected(val message: String) : GeminiConnectionResult
    data class Failed(val message: String) : GeminiConnectionResult
}

class GeminiApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
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
                    response.code == 401 || response.code == 403 ->
                        GeminiConnectionResult.Failed("The key was rejected. Check the key and its API access.")
                    response.code == 429 ->
                        GeminiConnectionResult.Failed("Gemini rate limit reached. Try again later.")
                    else -> GeminiConnectionResult.Failed("Gemini returned HTTP ${response.code}.")
                }
            }
        } catch (_: IOException) {
            GeminiConnectionResult.Failed("Could not reach Gemini. Check your connection and try again.")
        } catch (_: Exception) {
            GeminiConnectionResult.Failed("Connection validation failed. Please try again.")
        }
    }
}
