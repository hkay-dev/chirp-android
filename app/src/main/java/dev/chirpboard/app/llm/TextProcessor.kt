package dev.chirpboard.app.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

class TextProcessor(
    private val apiKey: String,
    private val modelName: String
) {
    companion object {
        private const val TAG = "TextProcessor"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/"

        // Code-like patterns for Smart mode detection
        private val CODE_PATTERNS = listOf(
            "function", "def ", "class ", "var ", "const ", "let ",
            "public ", "private ", "protected ", "static ",
            "import ", "export ", "return ", "if (", "for (", "while (",
            "->", "=>", "::", "&&", "||", "==", "!="
        )
        private val CODE_REGEX = Regex("[(){}\\[\\];]")

        // Email-like patterns for Smart mode detection
        private val EMAIL_PATTERNS = listOf(
            "dear", "hi ", "hello", "regards", "sincerely",
            "best regards", "kind regards", "thank you", "thanks"
        )
    }

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GeminiApi::class.java)

    suspend fun process(text: String, mode: ProcessingMode): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = resolvePrompt(text, mode)
            val fullPrompt = prompt + text + "\n</transcript>"
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

    private fun resolvePrompt(text: String, mode: ProcessingMode): String {
        return when (mode) {
            is ProcessingMode.Smart -> detectContentType(text).prompt!!
            is ProcessingMode.Custom -> mode.customPrompt
            else -> mode.prompt!!
        }
    }

    private fun detectContentType(text: String): ProcessingMode {
        val lowerText = text.lowercase()

        // Check for email patterns
        if (EMAIL_PATTERNS.any { lowerText.contains(it) }) {
            return ProcessingMode.Email
        }

        // Check for code patterns
        if (CODE_PATTERNS.any { lowerText.contains(it) } || CODE_REGEX.containsMatchIn(text)) {
            return ProcessingMode.Code
        }

        // Default to Formal
        return ProcessingMode.Formal
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
