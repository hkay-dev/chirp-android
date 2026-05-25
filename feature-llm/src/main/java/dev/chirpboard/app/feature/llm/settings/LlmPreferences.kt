package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "llm_settings")

@Singleton
class LlmPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private object Keys {
            val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
            val AUTO_TITLE = booleanPreferencesKey("auto_title")
            val AUTO_SUMMARY = booleanPreferencesKey("auto_summary")
        }

        companion object {
            private const val TAG = "LlmPreferences"
            private const val SECURE_PREFS_NAME = "secure_prefs"
            private const val APP_PREFS_NAME = "chirp"
            private const val GEMINI_CREDENTIAL_PREF = "gemini_api_key"
            private const val KEY_GEMINI_MODEL = "gemini_model"
        }

        private val appPrefs: SharedPreferences by lazy {
            context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Encrypted SharedPreferences for secure API key storage.
         * Returns null if encryption is unavailable (rare device-specific issues).
         */
        private val securePrefs: SharedPreferences? by lazy { createSecurePrefs() }

        private val _apiKey = MutableStateFlow<String?>(null)

        init {
            migrateStoredModelNameIfNeeded()
            _apiKey.value = readStoredApiKey()
        }

        val llmEnabled: Flow<Boolean> =
            context.dataStore.data.map { preferences ->
                preferences[Keys.LLM_ENABLED] ?: true // On by default for dev
            }

        /** API key updates whenever secure storage changes. */
        val apiKey: Flow<String?> = _apiKey.asStateFlow()

        fun fetchApiKey(): String? {
            val stored = readStoredApiKey()
            if (_apiKey.value != stored) {
                _apiKey.value = stored
            }
            return stored
        }

        fun getModelName(): String = resolveGeminiModelName(appPrefs.getString(KEY_GEMINI_MODEL, null))

        val autoTitle: Flow<Boolean> =
            context.dataStore.data.map { preferences ->
                preferences[Keys.AUTO_TITLE] ?: false
            }

        val autoSummary: Flow<Boolean> =
            context.dataStore.data.map { preferences ->
                preferences[Keys.AUTO_SUMMARY] ?: false
            }

        suspend fun setLlmEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[Keys.LLM_ENABLED] = enabled
            }
        }

        /**
         * Stores API key in encrypted SharedPreferences.
         */
        suspend fun setApiKey(key: String) {
            val normalized = key.trim()
            val prefs = securePrefs
            if (prefs == null) {
                Log.e(TAG, "Cannot save API key: secure storage unavailable")
                return
            }

            val committed = prefs.edit().putString(GEMINI_CREDENTIAL_PREF, normalized).commit()
            if (committed) {
                _apiKey.value = normalized
            } else {
                Log.e(TAG, "Failed to commit API key to secure storage")
            }
        }

        fun setModelName(modelName: String) {
            appPrefs.edit().putString(KEY_GEMINI_MODEL, resolveGeminiModelName(modelName)).apply()
        }

        suspend fun clearApiKey() {
            val prefs = securePrefs ?: return
            if (prefs.edit().remove(GEMINI_CREDENTIAL_PREF).commit()) {
                _apiKey.value = null
            }
        }

        private fun createSecurePrefs(): SharedPreferences? =
            try {
                val masterKey =
                    MasterKey
                        .Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()

                EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
                null
            }

        private fun readStoredApiKey(): String? = securePrefs?.getString(GEMINI_CREDENTIAL_PREF, null)

        private fun migrateStoredModelNameIfNeeded() {
            val stored = appPrefs.getString(KEY_GEMINI_MODEL, null) ?: return
            val resolved = resolveGeminiModelName(stored)
            if (stored != resolved) {
                Log.i(TAG, "Migrating Gemini model from $stored to $resolved")
                appPrefs.edit().putString(KEY_GEMINI_MODEL, resolved).apply()
            }
        }

        /**
         * Check if an API key has been configured.
         */
        fun hasApiKey(): Boolean = !fetchApiKey().isNullOrBlank()

        /**
         * Check if secure storage is available on this device.
         */
        fun isSecureStorageAvailable(): Boolean = securePrefs != null

        suspend fun setAutoTitle(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[Keys.AUTO_TITLE] = enabled
            }
        }

        suspend fun setAutoSummary(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[Keys.AUTO_SUMMARY] = enabled
            }
        }

        /** Get current autoTitle value synchronously (for workers) */
        suspend fun getAutoTitle(): Boolean = autoTitle.first()

        /** Get current autoSummary value synchronously (for workers) */
        suspend fun getAutoSummary(): Boolean = autoSummary.first()

        /** Get current llmEnabled value synchronously (for workers) */
        suspend fun getLlmEnabled(): Boolean = llmEnabled.first()
    }
