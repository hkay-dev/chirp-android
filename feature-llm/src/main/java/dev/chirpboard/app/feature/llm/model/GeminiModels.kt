package dev.chirpboard.app.feature.llm.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
@Keep
data class GeminiRequest(
    @SerializedName("contents")
    val contents: List<Content>,
    @SerializedName("systemInstruction")
    val systemInstruction: Content? = null,
    @SerializedName("generationConfig")
    val generationConfig: GenerationConfig? = null
) {
    @Keep
    data class Content(
        @SerializedName("role")
        val role: String? = null,
        @SerializedName("parts")
        val parts: List<Part>
    )

    @Keep
    data class Part(
        @SerializedName("text")
        val text: String
    )

    @Keep
    data class GenerationConfig(
        @SerializedName("maxOutputTokens")
        val maxOutputTokens: Int
    )

    companion object {
        fun of(text: String, maxOutputTokens: Int? = null) = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = text)))),
            generationConfig = maxOutputTokens?.let { GenerationConfig(maxOutputTokens = it) }
        )
    }
}

@Keep
data class GeminiResponse(
    @SerializedName("candidates")
    val candidates: List<Candidate>?,
    @SerializedName("error")
    val error: ApiError?
) {
    @Keep
    data class Candidate(
        @SerializedName("content")
        val content: Content?,
        @SerializedName("finishReason")
        val finishReason: String? = null
    )

    @Keep
    data class Content(
        @SerializedName("parts")
        val parts: List<Part>?
    )

    @Keep
    data class Part(
        @SerializedName("text")
        val text: String?
    )

    @Keep
    data class ApiError(
        @SerializedName("code")
        val code: Int?,
        @SerializedName("message")
        val message: String?
    )

    fun extractText(): String? = candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
}
