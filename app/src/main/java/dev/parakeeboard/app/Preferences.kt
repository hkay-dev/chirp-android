package dev.parakeeboard.app

import android.content.Context

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("parakeeboard", Context.MODE_PRIVATE)

    var llmEnabled: Boolean
        get() = prefs.getBoolean(KEY_LLM_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LLM_ENABLED, value).apply()

    companion object {
        private const val KEY_LLM_ENABLED = "llm_enabled"
    }
}
