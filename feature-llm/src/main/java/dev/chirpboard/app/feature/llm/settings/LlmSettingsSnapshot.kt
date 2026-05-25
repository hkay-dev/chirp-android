package dev.chirpboard.app.feature.llm.settings

import com.google.gson.annotations.SerializedName

/** Portable LLM settings payload encrypted inside a backup file. */
data class LlmSettingsSnapshot(
    @SerializedName("version") val version: Int = CURRENT_VERSION,
    @SerializedName("activeProvider") val activeProvider: String,
    @SerializedName("models") val models: Map<String, String>,
    @SerializedName("apiKeys") val apiKeys: Map<String, String>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
