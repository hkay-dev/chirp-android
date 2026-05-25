package dev.chirpboard.app.feature.llm.client

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

data class StructuredOutcomeExtraction(
    val tasks: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val followUps: List<String> = emptyList(),
)

internal fun parseStructuredOutcomeExtractionResponse(
    responseText: String,
    gson: Gson = Gson(),
): Result<StructuredOutcomeExtraction> {
    return try {
        val payload =
            gson.fromJson(
                responseText.unwrapJsonCodeFence(),
                StructuredOutcomeExtractionResponse::class.java,
            ) ?: return Result.failure(Exception("Empty structured outcome payload"))

        Result.success(
            StructuredOutcomeExtraction(
                tasks = payload.tasks.normalizeStructuredOutcomeItems(),
                decisions = payload.decisions.normalizeStructuredOutcomeItems(),
                followUps = payload.followUps.normalizeStructuredOutcomeItems(),
            ),
        )
    } catch (error: JsonSyntaxException) {
        Result.failure(Exception("Couldn't parse structured outcome response", error))
    }
}

private data class StructuredOutcomeExtractionResponse(
    @SerializedName("tasks")
    val tasks: List<String>? = null,
    @SerializedName("decisions")
    val decisions: List<String>? = null,
    @SerializedName(value = "followUps", alternate = ["follow_ups", "followups"])
    val followUps: List<String>? = null,
)

private fun String.unwrapJsonCodeFence(): String =
    trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

private fun List<String>?.normalizeStructuredOutcomeItems(): List<String> =
    this.orEmpty()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
