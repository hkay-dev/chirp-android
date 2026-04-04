package dev.chirpboard.app

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Preferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences = context.getSharedPreferences("chirp", Context.MODE_PRIVATE)

    var llmEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_LLM_ENABLED, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_LLM_ENABLED, value).apply()

    var microphoneGain: Float
        get() = sharedPreferences.getFloat(KEY_MICROPHONE_GAIN, 1.0f)
        set(value) = sharedPreferences.edit().putFloat(KEY_MICROPHONE_GAIN, value.coerceIn(1.0f, 5.0f)).apply()

    /**
     * @deprecated Use SecurePreferences.geminiApiKey instead for secure storage.
     * This property is kept for migration purposes only.
     */
    @Deprecated("Use SecurePreferences.geminiApiKey instead")
    var geminiApiKey: String
        get() = sharedPreferences.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    /**
     * Clears the API key from plaintext storage (used during migration).
     */
    fun clearGeminiApiKey() {
        sharedPreferences.edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    var geminiModel: String
        get() = sharedPreferences.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sharedPreferences.edit().putString(KEY_GEMINI_MODEL, value).apply()

    var hapticEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_HAPTIC_ENABLED, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_HAPTIC_ENABLED, value).apply()

    companion object {
        private const val KEY_LLM_ENABLED = "llm_enabled"
        private const val KEY_MICROPHONE_GAIN = "microphone_gain"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        
        private const val DEFAULT_MODEL = "gemini-3.1-flash-lite-preview"
    }
}
