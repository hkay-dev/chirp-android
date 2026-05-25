package dev.chirpboard.app.feature.llm.client

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import dev.chirpboard.app.feature.llm.model.ChatMessage
import dev.chirpboard.app.feature.llm.model.GeminiRequest
import dev.chirpboard.app.feature.llm.model.GeminiResponse
import dev.chirpboard.app.feature.llm.settings.LlmProvider
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmChatService
    @Inject
    constructor(
        private val preferences: LlmPreferences,
    ) {
        private val gson = Gson()
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        private val httpClient =
            OkHttpClient
                .Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

        suspend fun completePrompt(prompt: String): Result<String> =
            withContext(Dispatchers.IO) {
                val provider = preferences.getActiveProvider()
                val apiKey = preferences.fetchApiKeyFor(provider)?.trim().orEmpty()
                val model = preferences.getModelFor(provider)
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(
                        Exception("API key not configured. Add your ${provider.displayName} key in Settings."),
                    )
                }

                executeWithRetry(provider.displayName) {
                    when (provider) {
                        LlmProvider.GEMINI -> completeGeminiPrompt(apiKey, model, prompt)
                        LlmProvider.ANTHROPIC -> completeAnthropicPrompt(apiKey, model, prompt)
                        LlmProvider.OPENAI -> completeOpenAiCompatiblePrompt(OPENAI_CHAT_URL, apiKey, model, prompt)
                        LlmProvider.GROQ -> completeOpenAiCompatiblePrompt(GROQ_CHAT_URL, apiKey, model, prompt)
                        LlmProvider.CEREBRAS -> completeOpenAiCompatiblePrompt(CEREBRAS_CHAT_URL, apiKey, model, prompt)
                    }
                }
            }

        suspend fun completeChat(
            systemPrompt: String,
            messages: List<ChatMessage>,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                val provider = preferences.getActiveProvider()
                val apiKey = preferences.fetchApiKeyFor(provider)?.trim().orEmpty()
                val model = preferences.getModelFor(provider)
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(Exception("API key not configured"))
                }

                executeWithRetry("chat") {
                    when (provider) {
                        LlmProvider.GEMINI -> completeGeminiChat(apiKey, model, systemPrompt, messages)
                        LlmProvider.ANTHROPIC -> completeAnthropicChat(apiKey, model, systemPrompt, messages)
                        LlmProvider.OPENAI -> completeOpenAiCompatibleChat(OPENAI_CHAT_URL, apiKey, model, systemPrompt, messages)
                        LlmProvider.GROQ -> completeOpenAiCompatibleChat(GROQ_CHAT_URL, apiKey, model, systemPrompt, messages)
                        LlmProvider.CEREBRAS -> completeOpenAiCompatibleChat(CEREBRAS_CHAT_URL, apiKey, model, systemPrompt, messages)
                    }
                }
            }

        private suspend fun executeWithRetry(
            operationName: String,
            block: suspend () -> Result<String>,
        ): Result<String> {
            var currentDelay = 1_000L
            var lastException: Exception? = null

            for (attempt in 1..3) {
                val result =
                    try {
                        block()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Result.failure(e)
                    }

                if (result.isSuccess) {
                    return result
                }

                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                if (!shouldRetry(error) || attempt == 3) {
                    Log.e(TAG, "$operationName failed", error)
                    return Result.failure(error)
                }

                Log.w(TAG, "Retrying $operationName after transient error (attempt $attempt)", error)
                lastException = error as? Exception ?: Exception(error.message, error)
                delay(currentDelay)
                currentDelay *= 2
            }

            return Result.failure(lastException ?: Exception("Max retries exceeded"))
        }

        private fun shouldRetry(error: Throwable): Boolean =
            when (error) {
                is IOException -> true
                is LlmHttpException -> error.code == 429 || error.code == 503 || error.code >= 500
                else -> false
            }

        private fun completeGeminiPrompt(
            apiKey: String,
            model: String,
            prompt: String,
        ): Result<String> {
            val requestBody = gson.toJson(GeminiRequest.of(prompt))
            val url = "$GEMINI_BASE_URL/v1beta/models/$model:generateContent?key=$apiKey"
            return postJson(url, requestBody, emptyMap()).mapCatching { body ->
                val response = gson.fromJson(body, GeminiResponse::class.java)
                if (response.error != null) {
                    throw Exception(response.error.message ?: "Gemini API error")
                }
                response.extractText()?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw Exception("Empty response")
            }
        }

        private fun completeGeminiChat(
            apiKey: String,
            model: String,
            systemPrompt: String,
            messages: List<ChatMessage>,
        ): Result<String> {
            val contents = mutableListOf<GeminiRequest.Content>()
            var firstUser = true
            for (message in messages) {
                val role = if (message.isFromUser) "user" else "model"
                val text =
                    if (firstUser && message.isFromUser) {
                        firstUser = false
                        "$systemPrompt\n\nUser Question:\n${message.text}"
                    } else {
                        message.text
                    }
                contents.add(
                    GeminiRequest.Content(
                        role = role,
                        parts = listOf(GeminiRequest.Part(text = text)),
                    ),
                )
            }

            val requestBody = gson.toJson(GeminiRequest(contents = contents))
            val url = "$GEMINI_BASE_URL/v1beta/models/$model:generateContent?key=$apiKey"
            return postJson(url, requestBody, emptyMap()).mapCatching { body ->
                val response = gson.fromJson(body, GeminiResponse::class.java)
                if (response.error != null) {
                    throw Exception(response.error.message ?: "Gemini API error")
                }
                response.extractText()?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw Exception("Empty response")
            }
        }

        private fun completeOpenAiCompatiblePrompt(
            url: String,
            apiKey: String,
            model: String,
            prompt: String,
        ): Result<String> {
            val payload =
                OpenAiChatRequest(
                    model = model,
                    messages =
                        listOf(
                            OpenAiChatMessage(role = "user", content = prompt),
                        ),
                )
            return postJson(url, gson.toJson(payload), bearerHeaders(apiKey)).mapCatching { body ->
                parseOpenAiText(body, model)
            }
        }

        private fun completeOpenAiCompatibleChat(
            url: String,
            apiKey: String,
            model: String,
            systemPrompt: String,
            messages: List<ChatMessage>,
        ): Result<String> {
            val chatMessages =
                buildList {
                    add(OpenAiChatMessage(role = "system", content = systemPrompt))
                    messages.forEach { message ->
                        add(
                            OpenAiChatMessage(
                                role = if (message.isFromUser) "user" else "assistant",
                                content = message.text,
                            ),
                        )
                    }
                }
            val payload = OpenAiChatRequest(model = model, messages = chatMessages)
            return postJson(url, gson.toJson(payload), bearerHeaders(apiKey)).mapCatching { body ->
                parseOpenAiText(body, model)
            }
        }

        private fun completeAnthropicPrompt(
            apiKey: String,
            model: String,
            prompt: String,
        ): Result<String> {
            val payload =
                AnthropicRequest(
                    model = model,
                    maxTokens = DEFAULT_MAX_TOKENS,
                    system = null,
                    messages = listOf(AnthropicMessage(role = "user", content = prompt)),
                )
            return postJson(ANTHROPIC_MESSAGES_URL, gson.toJson(payload), anthropicHeaders(apiKey)).mapCatching { body ->
                parseAnthropicText(body, model)
            }
        }

        private fun completeAnthropicChat(
            apiKey: String,
            model: String,
            systemPrompt: String,
            messages: List<ChatMessage>,
        ): Result<String> {
            val payload =
                AnthropicRequest(
                    model = model,
                    maxTokens = DEFAULT_MAX_TOKENS,
                    system = systemPrompt,
                    messages =
                        messages.map { message ->
                            AnthropicMessage(
                                role = if (message.isFromUser) "user" else "assistant",
                                content = message.text,
                            )
                        },
                )
            return postJson(ANTHROPIC_MESSAGES_URL, gson.toJson(payload), anthropicHeaders(apiKey)).mapCatching { body ->
                parseAnthropicText(body, model)
            }
        }

        private fun parseOpenAiText(
            body: String,
            model: String,
        ): String {
            val response = gson.fromJson(body, OpenAiChatResponse::class.java)
            val text = response.choices?.firstOrNull()?.message?.content?.trim()
            if (!text.isNullOrBlank()) {
                return text
            }
            val errorMessage = response.error?.message ?: extractErrorMessage(body)
            if (!errorMessage.isNullOrBlank()) {
                throw Exception(errorMessage)
            }
            throw Exception("Empty response from $model")
        }

        private fun parseAnthropicText(
            body: String,
            model: String,
        ): String {
            val response = gson.fromJson(body, AnthropicResponse::class.java)
            val text = response.content?.firstOrNull()?.text?.trim()
            if (!text.isNullOrBlank()) {
                return text
            }
            val errorMessage = response.error?.message ?: extractErrorMessage(body)
            if (!errorMessage.isNullOrBlank()) {
                throw Exception(errorMessage)
            }
            throw Exception("Empty response from $model")
        }

        private fun extractErrorMessage(body: String): String? =
            runCatching {
                val json = gson.fromJson(body, JsonObject::class.java)
                json.getAsJsonObject("error")?.get("message")?.asString
            }.getOrNull()

        private fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): Result<String> {
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody(jsonMediaType))

            headers.forEach { (key, value) -> requestBuilder.header(key, value) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = extractErrorMessage(body) ?: "HTTP ${response.code}"
                    if (response.code == 404) {
                        throw LlmHttpException(response.code, "Model not found: $message")
                    }
                    throw LlmHttpException(response.code, message)
                }
                return Result.success(body)
            }
        }

        private fun bearerHeaders(apiKey: String): Map<String, String> =
            mapOf("Authorization" to "Bearer $apiKey")

        private fun anthropicHeaders(apiKey: String): Map<String, String> =
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            )

        private class LlmHttpException(
            val code: Int,
            message: String,
        ) : Exception(message)

        private data class OpenAiChatRequest(
            @SerializedName("model") val model: String,
            @SerializedName("messages") val messages: List<OpenAiChatMessage>,
        )

        private data class OpenAiChatMessage(
            @SerializedName("role") val role: String,
            @SerializedName("content") val content: String,
        )

        private data class OpenAiChatResponse(
            @SerializedName("choices") val choices: List<OpenAiChoice>? = null,
            @SerializedName("error") val error: OpenAiError? = null,
        )

        private data class OpenAiChoice(
            @SerializedName("message") val message: OpenAiChatMessage? = null,
        )

        private data class OpenAiError(
            @SerializedName("message") val message: String? = null,
        )

        private data class AnthropicRequest(
            @SerializedName("model") val model: String,
            @SerializedName("max_tokens") val maxTokens: Int,
            @SerializedName("system") val system: String?,
            @SerializedName("messages") val messages: List<AnthropicMessage>,
        )

        private data class AnthropicMessage(
            @SerializedName("role") val role: String,
            @SerializedName("content") val content: String,
        )

        private data class AnthropicResponse(
            @SerializedName("content") val content: List<AnthropicContentBlock>? = null,
            @SerializedName("error") val error: AnthropicError? = null,
        )

        private data class AnthropicContentBlock(
            @SerializedName("text") val text: String? = null,
        )

        private data class AnthropicError(
            @SerializedName("message") val message: String? = null,
        )

        companion object {
            private const val TAG = "LlmChatService"
            private const val DEFAULT_MAX_TOKENS = 4096
            private const val ANTHROPIC_VERSION = "2023-06-01"
            private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
            private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
            private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
            private const val CEREBRAS_CHAT_URL = "https://api.cerebras.ai/v1/chat/completions"
            private const val ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        }
    }
