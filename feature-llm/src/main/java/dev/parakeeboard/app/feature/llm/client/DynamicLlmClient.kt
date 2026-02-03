package dev.parakeeboard.app.feature.llm.client

import android.util.Log
import dev.parakeeboard.app.feature.llm.model.GeminiRequest
import dev.parakeeboard.app.feature.llm.model.GeminiResponse
import dev.parakeeboard.app.feature.llm.settings.LlmPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dynamic LLM client that fetches API key from preferences at runtime.
 * This ensures the user's configured API key is always used.
 */
@Singleton
class DynamicLlmClient @Inject constructor(
    private val preferences: LlmPreferences
) : LlmClient {

    companion object {
        private const val TAG = "DynamicLlmClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/"
        private const val DEFAULT_MODEL = "gemini-2.0-flash"

        private const val TITLE_PROMPT = """Generate a brief, descriptive title (5-8 words max) for this voice recording transcript. 
Return ONLY the title text, nothing else. No quotes, no explanation.

Transcript:
"""

        private const val SUMMARY_PROMPT = """Summarize this voice recording transcript in 2-3 sentences.
Focus on the main points and key information.
Return ONLY the summary text, nothing else.

Transcript:
"""
    }

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GeminiApi::class.java)

    private suspend fun getApiKey(): String? {
        return preferences.apiKey.first()
    }

    override suspend fun process(text: String, systemPrompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API key not configured")
            return@withContext Result.failure(Exception("API key not configured. Please set your Gemini API key in Settings."))
        }

        try {
            val fullPrompt = systemPrompt + text + "\n</transcript>"
            val request = GeminiRequest.of(fullPrompt)
            val url = "v1beta/models/$DEFAULT_MODEL:generateContent"
            val response = api.generate(url, apiKey, request)

            if (response.error != null) {
                Log.e(TAG, "API error: ${response.error.message}")
                return@withContext Result.failure(Exception(response.error.message ?: "API error"))
            }

            val resultText = response.extractText()
            if (resultText.isNullOrBlank()) {
                Log.e(TAG, "Empty response")
                return@withContext Result.failure(Exception("Empty response"))
            }

            Result.success(resultText.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed", e)
            Result.failure(e)
        }
    }

    override suspend fun generateTitle(transcript: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API key not configured for title generation")
            return@withContext Result.failure(Exception("API key not configured"))
        }

        try {
            val fullPrompt = TITLE_PROMPT + transcript
            val request = GeminiRequest.of(fullPrompt)
            val url = "v1beta/models/$DEFAULT_MODEL:generateContent"
            val response = api.generate(url, apiKey, request)

            if (response.error != null) {
                Log.e(TAG, "API error generating title: ${response.error.message}")
                return@withContext Result.failure(Exception(response.error.message ?: "API error"))
            }

            val title = response.extractText()
            if (title.isNullOrBlank()) {
                Log.e(TAG, "Empty title response")
                return@withContext Result.failure(Exception("Empty response"))
            }

            Result.success(title.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Title generation failed", e)
            Result.failure(e)
        }
    }

    override suspend fun generateSummary(transcript: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API key not configured for summary generation")
            return@withContext Result.failure(Exception("API key not configured"))
        }

        try {
            val fullPrompt = SUMMARY_PROMPT + transcript
            val request = GeminiRequest.of(fullPrompt)
            val url = "v1beta/models/$DEFAULT_MODEL:generateContent"
            val response = api.generate(url, apiKey, request)

            if (response.error != null) {
                Log.e(TAG, "API error generating summary: ${response.error.message}")
                return@withContext Result.failure(Exception(response.error.message ?: "API error"))
            }

            val summary = response.extractText()
            if (summary.isNullOrBlank()) {
                Log.e(TAG, "Empty summary response")
                return@withContext Result.failure(Exception("Empty response"))
            }

            Result.success(summary.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Summary generation failed", e)
            Result.failure(e)
        }
    }

    private interface GeminiApi {
        @POST
        suspend fun generate(
            @Url url: String,
            @Query("key") apiKey: String,
            @Body request: GeminiRequest
        ): GeminiResponse
    }
}
