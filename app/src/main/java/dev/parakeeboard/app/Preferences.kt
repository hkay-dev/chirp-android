package dev.parakeeboard.app

import android.content.Context

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("parakeeboard", Context.MODE_PRIVATE)

    var llmEnabled: Boolean
        get() = prefs.getBoolean(KEY_LLM_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LLM_ENABLED, value).apply()

    var microphoneGain: Float
        get() = prefs.getFloat(KEY_MICROPHONE_GAIN, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_MICROPHONE_GAIN, value.coerceIn(1.0f, 5.0f)).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    companion object {
        private const val KEY_LLM_ENABLED = "llm_enabled"
        private const val KEY_MICROPHONE_GAIN = "microphone_gain"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        
        private const val DEFAULT_API_KEY = ""
        private const val DEFAULT_MODEL = "gemini-flash-lite-latest"
    }
}
