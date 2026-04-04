package dev.chirpboard.app.feature.llm.client

import android.util.Log
import dev.chirpboard.app.feature.llm.model.GeminiRequest
import dev.chirpboard.app.feature.llm.model.GeminiResponse
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
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
 * Implementation of LlmClient that fetches API key from preferences at runtime.
 */
@Singleton
class LlmClientImpl
    @Inject
    constructor(
        private val preferences: LlmPreferences,
    ) : LlmClient {
        companion object {
            private const val TAG = "LlmClientImpl"
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

        private val api: GeminiApi =
            Retrofit
                .Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GeminiApi::class.java)

        private suspend fun executeRequest(
            prompt: String,
            operationName: String,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                val apiKey = preferences.apiKey.first()
                val modelName = preferences.getModelName()
                if (apiKey.isNullOrBlank()) {
                    Log.e(TAG, "API key not configured for $operationName")
                    return@withContext Result.failure(Exception("API key not configured. Please set your Gemini API key in Settings."))
                }

                try {
                    val request = GeminiRequest.of(prompt)
                    val url = "v1beta/models/$modelName:generateContent"
                    val response = api.generate(url, apiKey, request)

                    if (response.error != null) {
                        Log.e(TAG, "API error generating $operationName: ${response.error.message}")
                        return@withContext Result.failure(Exception(response.error.message ?: "API error"))
                    }

                    val resultText = response.extractText()
                    if (resultText.isNullOrBlank()) {
                        Log.e(TAG, "Empty $operationName response")
                        return@withContext Result.failure(Exception("Empty response"))
                    }

                    Result.success(resultText.trim())
                } catch (e: Exception) {
                    Log.e(TAG, "$operationName failed", e)
                    Result.failure(e)
                }
            }

        override suspend fun process(
            text: String,
            systemPrompt: String,
        ): Result<String> {
            val fullPrompt = systemPrompt + text + "\n</transcript>"
            return executeRequest(fullPrompt, "processing")
        }

        override suspend fun generateTitle(transcript: String): Result<String> {
            val fullPrompt = TITLE_PROMPT + transcript
            return executeRequest(fullPrompt, "title")
        }

        override suspend fun generateSummary(transcript: String): Result<String> {
            val fullPrompt = SUMMARY_PROMPT + transcript
            return executeRequest(fullPrompt, "summary")
        }

        private interface GeminiApi {
            @POST
            suspend fun generate(
                @Url url: String,
                @Query("key") apiKey: String,
                @Body request: GeminiRequest,
            ): GeminiResponse
        }
    }
