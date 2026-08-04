package dev.chirpboard.app.cloud

import androidx.annotation.Keep
import com.google.gson.Gson
import dev.chirpboard.app.di.CloudTranscriptionHttpClient
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class VertexTextGenerationClient
    @Inject
    constructor(
        @CloudTranscriptionHttpClient private val httpClient: OkHttpClient,
        private val authTokenProvider: CloudAuthTokenProvider,
        private val serviceConfiguration: CloudServiceConfiguration,
    ) {
        private val gson = Gson()
        private val baseUrl = serviceConfiguration.baseUrl.trim().trimEnd('/')

        suspend fun isConfigured(): Boolean =
            baseUrl.toHttpUrlOrNull()?.let { url ->
                url.isHttps ||
                    (serviceConfiguration.allowInsecureLoopback && url.host in setOf("127.0.0.1", "localhost"))
            } == true &&
                !authTokenProvider.getIdToken().isNullOrBlank()

        suspend fun generate(
            text: String,
            prompt: String,
            model: String?,
            recordingId: String?,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    require(text.isNotBlank()) { "Text is empty" }
                    require(prompt.isNotBlank()) { "Prompt is empty" }
                    val stableRecordingId =
                        recordingId
                            ?.trim()
                            ?.takeIf { it.length in MIN_RECORDING_ID_LENGTH..MAX_RECORDING_ID_LENGTH }
                            ?: return@withContext Result.failure(
                                PermanentVertexTextGenerationException("Recording ID is unavailable"),
                            )
                    val url = baseUrl.toHttpUrlOrNull()
                        ?.takeIf {
                            it.isHttps ||
                                (serviceConfiguration.allowInsecureLoopback && it.host in setOf("127.0.0.1", "localhost"))
                        }
                        ?: return@withContext Result.failure(IllegalStateException("Cloud endpoint is not configured"))
                    val token = authTokenProvider.getIdToken()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@withContext Result.failure(IllegalStateException("Cloud authentication is not configured"))
                    val body = gson.toJson(VertexGenerateRequest(text, prompt, model))
                    val idempotencyKey = stableRequestKey(stableRecordingId, body)
                    val request =
                        Request
                            .Builder()
                            .url("${url.toString().trimEnd('/')}/v1/text:generate")
                            .header("Authorization", "Bearer $token")
                            .header("Idempotency-Key", idempotencyKey)
                            .header("Recording-Id", stableRecordingId)
                            .post(body.toRequestBody(JSON_MEDIA_TYPE))
                            .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.code == 202) {
                            return@withContext Result.failure(
                                IOException("Vertex generation is still running"),
                            )
                        }
                        if (!response.isSuccessful) {
                            val message =
                                when (response.code) {
                                    401, 403 -> "Cloud authentication was rejected"
                                    408, 429 -> "Vertex generation is temporarily busy"
                                    in 500..599 -> "Vertex generation is temporarily unavailable"
                                    else -> "Vertex generation request was rejected"
                                }
                            return@withContext Result.failure(
                                if (response.code == 408 || response.code == 429 || response.code in 500..599) {
                                    IOException(message)
                                } else {
                                    PermanentVertexTextGenerationException(message)
                                },
                            )
                        }
                        val responseBody = response.body?.string()
                            ?: return@withContext Result.failure(IOException("Vertex returned an empty response"))
                        val generated =
                            runCatching { gson.fromJson(responseBody, VertexGenerateResponse::class.java) }
                                .getOrElse {
                                    return@withContext Result.failure(IOException("Vertex returned an invalid response"))
                                }
                        generated.text
                            .takeIf { it.isNotBlank() }
                            ?.let { Result.success(it) }
                            ?: Result.failure(IOException("Vertex returned empty text"))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }

        private fun stableRequestKey(
            recordingId: String,
            body: String,
        ): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$recordingId\n$body".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private companion object {
            val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
            const val MIN_RECORDING_ID_LENGTH = 8
            const val MAX_RECORDING_ID_LENGTH = 128
        }
    }

@Keep
private data class VertexGenerateRequest(
    val text: String,
    val prompt: String,
    val model: String?,
)

@Keep
private data class VertexGenerateResponse(
    val text: String,
    val model: String,
)

private class PermanentVertexTextGenerationException(
    message: String,
) : Exception(message)
