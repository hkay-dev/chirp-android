package dev.chirpboard.app.feature.llm.model

object ProcessingModeDefaults {
    const val DEFAULT_MODE_ID = "proofread"

    val builtInSelectableIds: List<String> =
        listOf("proofread", "formal", "casual", "email", "code", "smart")

    val editableBuiltInIds: List<String> =
        listOf("proofread", "formal", "casual", "email", "code")

    fun displayName(modeId: String): String =
        when (modeId) {
            "proofread" -> ProcessingMode.Proofread.displayName
            "formal" -> ProcessingMode.Formal.displayName
            "casual" -> ProcessingMode.Casual.displayName
            "email" -> ProcessingMode.Email.displayName
            "code" -> ProcessingMode.Code.displayName
            "smart" -> ProcessingMode.Smart.displayName
            else -> modeId
        }

    fun defaultPrompt(modeId: String): String? =
        when (modeId) {
            "proofread" -> ProcessingMode.Proofread.prompt
            "formal" -> ProcessingMode.Formal.prompt
            "casual" -> ProcessingMode.Casual.prompt
            "email" -> ProcessingMode.Email.prompt
            "code" -> ProcessingMode.Code.prompt
            "smart" -> null
            else -> null
        }

    fun isBuiltIn(modeId: String): Boolean = modeId in builtInSelectableIds

    fun isEditable(modeId: String): Boolean = modeId in editableBuiltInIds
}
