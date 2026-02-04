package dev.chirpboard.app.llm

import com.google.gson.annotations.SerializedName

data class GeminiRequest(
    @SerializedName("contents")
    val contents: List<Content>
) {
    data class Content(
        @SerializedName("parts")
        val parts: List<Part>
    )

    data class Part(
        @SerializedName("text")
        val text: String
    )

    companion object {
        fun of(text: String) = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = text))))
        )
    }
}

data class GeminiResponse(
    @SerializedName("candidates")
    val candidates: List<Candidate>?,
    @SerializedName("error")
    val error: ApiError?
) {
    data class Candidate(
        @SerializedName("content")
        val content: Content?
    )

    data class Content(
        @SerializedName("parts")
        val parts: List<Part>?
    )

    data class Part(
        @SerializedName("text")
        val text: String?
    )

    data class ApiError(
        @SerializedName("code")
        val code: Int?,
        @SerializedName("message")
        val message: String?
    )

    fun extractText(): String? = candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
}
