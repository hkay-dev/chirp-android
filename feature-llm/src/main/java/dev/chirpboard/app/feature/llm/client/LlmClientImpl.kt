package dev.chirpboard.app.feature.llm.client

import android.util.Log
import dev.chirpboard.app.feature.llm.model.GeminiRequest
import dev.chirpboard.app.feature.llm.model.GeminiResponse
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit
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

        private val okHttpClient = OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        private val api: GeminiApi =
            Retrofit
                .Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
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

                    var currentDelay = 1000L
                    var lastException: Exception? = null

                    for (attempt in 1..3) {
                        try {
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

                            return@withContext Result.success(resultText.trim())
                        } catch (e: Exception) {
                            val shouldRetry = when (e) {
                                is HttpException -> e.code() == 429 || e.code() == 503 || e.code() >= 500
                                is java.io.IOException -> true // Network errors like timeout, unknown host
                                else -> false
                            }
                            
                            if (shouldRetry) {
                                Log.w(TAG, "Network or server error for $operationName, attempt $attempt. Retrying in ${currentDelay}ms", e)
                                lastException = e
                                if (attempt < 3) {
                                    delay(currentDelay)
                                    currentDelay *= 2
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                    
                    val finalException = lastException ?: Exception("Max retries exceeded")
                    Log.e(TAG, "$operationName failed after retries", finalException)
                    Result.failure(finalException)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
        override suspend fun generateChatResponse(
            transcript: String,
            messages: List<dev.chirpboard.app.feature.llm.model.ChatMessage>
        ): Result<String> {
            val apiKey = preferences.apiKey.first()
            val modelName = preferences.getModelName()
            if (apiKey.isNullOrBlank()) {
                return Result.failure(Exception("API key not configured"))
            }
            
            val systemPrompt = "You are a helpful assistant analyzing a voice recording transcript. " +
                    "Answer the user's questions about this transcript. Keep answers concise.\n\n" +
                    "Transcript:\n$transcript"
            
            val contents = mutableListOf<GeminiRequest.Content>()
            // Gemini system instructions can sometimes be passed differently, but as a simple hack 
            // we can just put it as the first user message or a developer role if supported. 
            // Or just prepend it to the first user message.
            
            var firstUser = true
            for (msg in messages) {
                val role = if (msg.isFromUser) "user" else "model"
                val text = if (firstUser && msg.isFromUser) {
                    firstUser = false
                    systemPrompt + "\n\nUser Question:\n" + msg.text
                } else {
                    msg.text
                }
                contents.add(
                    GeminiRequest.Content(
                        role = role,
                        parts = listOf(GeminiRequest.Part(text = text))
                    )
                )
            }
            
            val request = GeminiRequest(contents = contents)
            val url = "v1beta/models/$modelName:generateContent"
            
            return withContext(Dispatchers.IO) {
                try {
                    val response = api.generate(url, apiKey, request)
                    if (response.error != null) {
                        Result.failure(Exception(response.error.message ?: "API error"))
                    } else {
                        val resultText = response.extractText()
                        if (resultText.isNullOrBlank()) {
                            Result.failure(Exception("Empty response"))
                        } else {
                            Result.success(resultText.trim())
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
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
