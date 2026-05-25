package dev.chirpboard.app.feature.llm.model

data class ProcessingPromptPreset(
    val id: String,
    val name: String,
    val prompt: String?,
    val originalPrompt: String?,
    val isBuiltIn: Boolean,
    val isModified: Boolean,
    val canEditPrompt: Boolean,
)

data class ProcessingModeListItem(
    val id: String,
    val name: String,
)
