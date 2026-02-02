package dev.parakeeboard.app.feature.llm.client

import android.util.Log
import dev.parakeeboard.app.feature.llm.model.GeminiRequest
import dev.parakeeboard.app.feature.llm.model.GeminiResponse
import kotlinx.coroutines.Dispatchers
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
 * Gemini API client implementing LlmClient interface.
 * Handles all communication with Google's Gemini API.
 */
@Singleton
class GeminiClient @Inject constructor(
    private val apiKey: String,
    private val modelName: String
) : LlmClient {

    companion object {
        private const val TAG = "GeminiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/"

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

    override suspend fun process(text: String, systemPrompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fullPrompt = systemPrompt + text + "\n</transcript>"
            val request = GeminiRequest.of(fullPrompt)
            val url = "v1beta/models/$modelName:generateContent"
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
        try {
            val fullPrompt = TITLE_PROMPT + transcript
            val request = GeminiRequest.of(fullPrompt)
            val url = "v1beta/models/$modelName:generateContent"
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
        try {
            val fullPrompt = SUMMARY_PROMPT + transcript
            val request = GeminiRequest.of(fullPrompt)
            val url = "v1beta/models/$modelName:generateContent"
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
