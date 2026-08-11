package dev.chirpboard.app.feature.llm.client

import android.util.Log
import androidx.annotation.Keep
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class LlmChatService
    @Inject
    constructor(
        private val preferences: LlmPreferences,
    ) {
        private val gson = Gson()
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        // callTimeout bounds the whole request including retriable DNS/connect stalls; without
        // it a black-holed endpoint held the settings "Test connection" spinner (and worker
        // enhancement attempts) for the OS socket timeout times three retries.
        private val httpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()

        suspend fun completePrompt(prompt: String): Result<String> =
            withContext(Dispatchers.IO) {
                completeResolvedPrompt(preferences.getActiveProvider(), modelId = null, prompt = prompt)
            }

        suspend fun completePrompt(
            providerId: String?,
            modelId: String?,
            prompt: String,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                completeResolvedPrompt(LlmProvider.fromId(providerId), modelId = modelId, prompt = prompt)
            }

        private suspend fun completeResolvedPrompt(
            provider: LlmProvider,
            modelId: String?,
            prompt: String,
        ): Result<String> {
            val apiKey = preferences.fetchApiKeyFor(provider)?.trim().orEmpty()
            val model = modelId?.takeIf { it.isNotBlank() } ?: preferences.getModelFor(provider)
            if (apiKey.isBlank()) {
                return Result.failure(
                    Exception("API key not configured. Add your ${provider.displayName} key in Settings."),
                )
            }

            return executeWithRetry(provider.displayName) {
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
            var attempt = 1
            while (true) {
                val result =
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }

                if (result.isSuccess) {
                    return result
                }

                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                if (!shouldRetry(error) || attempt == MAX_ATTEMPTS) {
                    Log.e(TAG, "$operationName failed", error)
                    return Result.failure(error)
                }

                Log.w(TAG, "Retrying $operationName after transient error (attempt $attempt)", error)
                delay(currentDelay)
                currentDelay *= 2
                attempt++
            }
        }

        private fun shouldRetry(error: Throwable): Boolean =
            when (error) {
                is IOException -> true
                is LlmHttpException -> error.code == 429 || error.code >= 500
                else -> false
            }

        private suspend fun completeGeminiPrompt(
            apiKey: String,
            model: String,
            prompt: String,
        ): Result<String> {
            val requestBody = gson.toJson(GeminiRequest.of(prompt))
            val url = "$GEMINI_BASE_URL/v1beta/models/$model:generateContent"
            return postJson(url, requestBody, geminiHeaders(apiKey)).mapCatching { body ->
                val response = gson.fromJson(body, GeminiResponse::class.java)
                if (response.error != null) {
                    throw Exception(response.error.message ?: "Gemini API error")
                }
                response.extractText()?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw Exception("Empty response")
            }
        }

        private suspend fun completeGeminiChat(
            apiKey: String,
            model: String,
            systemPrompt: String,
            messages: List<ChatMessage>,
        ): Result<String> {
            val contents =
                messages.map { message ->
                    GeminiRequest.Content(
                        role = if (message.isFromUser) "user" else "model",
                        parts = listOf(GeminiRequest.Part(text = message.text)),
                    )
                }
            val systemInstruction =
                systemPrompt
                    .takeIf { it.isNotBlank() }
                    ?.let { GeminiRequest.Content(parts = listOf(GeminiRequest.Part(text = it))) }
            val requestBody = gson.toJson(GeminiRequest(contents = contents, systemInstruction = systemInstruction))
            val url = "$GEMINI_BASE_URL/v1beta/models/$model:generateContent"
            return postJson(url, requestBody, geminiHeaders(apiKey)).mapCatching { body ->
                val response = gson.fromJson(body, GeminiResponse::class.java)
                if (response.error != null) {
                    throw Exception(response.error.message ?: "Gemini API error")
                }
                response.extractText()?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw Exception("Empty response")
            }
        }

        private suspend fun completeOpenAiCompatiblePrompt(
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

        private suspend fun completeOpenAiCompatibleChat(
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

        private suspend fun completeAnthropicPrompt(
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

        private suspend fun completeAnthropicChat(
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

        private suspend fun postJson(
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

            awaitResponse(httpClient.newCall(requestBuilder.build())).use { response ->
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

        // A blocking execute() ignores coroutine cancellation: a stopped worker or a closed
        // settings screen would keep the socket and an IO thread busy until timeout. enqueue +
        // invokeOnCancellation aborts the request the moment the caller is cancelled.
        private suspend fun awaitResponse(call: Call): Response =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            continuation.resumeWithException(e)
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            if (continuation.isActive) continuation.resume(response) else response.close()
                        }
                    },
                )
            }

        private fun bearerHeaders(apiKey: String): Map<String, String> =
            mapOf("Authorization" to "Bearer $apiKey")

        // The key goes in a header, never the URL: URLs leak into server/proxy logs and
        // exception traces, while headers stay out of them (SEC-3).
        private fun geminiHeaders(apiKey: String): Map<String, String> =
            mapOf("x-goog-api-key" to apiKey)

        private fun anthropicHeaders(apiKey: String): Map<String, String> =
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            )

        private class LlmHttpException(
            val code: Int,
            message: String,
        ) : Exception(message)

        // REL-02/REL-05: every class below is (de)serialized by Gson REFLECTION. Without @Keep,
        // R8 full mode strips/merges them (mapping.txt proved AnthropicResponse and friends were
        // class-merged into icon caches with zero fields), making fromJson return all-null
        // shells SILENTLY. Keep @Keep on each one, matching GeminiModels.kt.

        @Keep
        private data class OpenAiChatRequest(
            @SerializedName("model") val model: String,
            @SerializedName("messages") val messages: List<OpenAiChatMessage>,
        )

        @Keep
        private data class OpenAiChatMessage(
            @SerializedName("role") val role: String,
            @SerializedName("content") val content: String,
        )

        @Keep
        private data class OpenAiChatResponse(
            @SerializedName("choices") val choices: List<OpenAiChoice>? = null,
            @SerializedName("error") val error: OpenAiError? = null,
        )

        @Keep
        private data class OpenAiChoice(
            @SerializedName("message") val message: OpenAiChatMessage? = null,
        )

        @Keep
        private data class OpenAiError(
            @SerializedName("message") val message: String? = null,
        )

        @Keep
        private data class AnthropicRequest(
            @SerializedName("model") val model: String,
            @SerializedName("max_tokens") val maxTokens: Int,
            @SerializedName("system") val system: String?,
            @SerializedName("messages") val messages: List<AnthropicMessage>,
        )

        @Keep
        private data class AnthropicMessage(
            @SerializedName("role") val role: String,
            @SerializedName("content") val content: String,
        )

        @Keep
        private data class AnthropicResponse(
            @SerializedName("content") val content: List<AnthropicContentBlock>? = null,
            @SerializedName("error") val error: AnthropicError? = null,
        )

        @Keep
        private data class AnthropicContentBlock(
            @SerializedName("text") val text: String? = null,
        )

        @Keep
        private data class AnthropicError(
            @SerializedName("message") val message: String? = null,
        )

        companion object {
            private const val TAG = "LlmChatService"
            private const val MAX_ATTEMPTS = 3
            private const val DEFAULT_MAX_TOKENS = 4096
            private const val ANTHROPIC_VERSION = "2023-06-01"
            private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
            private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
            private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
            private const val CEREBRAS_CHAT_URL = "https://api.cerebras.ai/v1/chat/completions"
            private const val ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        }
    }
