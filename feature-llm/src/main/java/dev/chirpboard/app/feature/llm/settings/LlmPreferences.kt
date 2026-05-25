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
            private const val LEGACY_GEMINI_CREDENTIAL_PREF = "gemini_api_key"
            private const val LEGACY_GEMINI_MODEL_PREF = "gemini_model"
            private const val KEY_ACTIVE_PROVIDER = "llm_active_provider"

            private fun apiKeyPrefKey(provider: LlmProvider): String = "llm_api_key_${provider.id}"

            private fun modelPrefKey(provider: LlmProvider): String = "llm_model_${provider.id}"
        }

        private val appPrefs: SharedPreferences by lazy {
            context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        }

        private val securePrefs: SharedPreferences? by lazy { createSecurePrefs() }

        private val _activeProvider = MutableStateFlow(LlmProvider.GEMINI)
        private val _apiKey = MutableStateFlow<String?>(null)

        init {
            migrateLegacyGeminiSettingsIfNeeded()
            migrateStoredModelNamesIfNeeded()
            _activeProvider.value = getActiveProvider()
            refreshActiveApiKey()
        }

        val llmEnabled: Flow<Boolean> =
            context.dataStore.data.map { preferences ->
                preferences[Keys.LLM_ENABLED] ?: true
            }

        val activeProvider: Flow<LlmProvider> = _activeProvider.asStateFlow()

        /** API key for the currently selected provider. */
        val apiKey: Flow<String?> = _apiKey.asStateFlow()

        fun getActiveProvider(): LlmProvider = LlmProvider.fromId(appPrefs.getString(KEY_ACTIVE_PROVIDER, null))

        fun setActiveProvider(provider: LlmProvider) {
            appPrefs.edit().putString(KEY_ACTIVE_PROVIDER, provider.id).apply()
            _activeProvider.value = provider
            refreshActiveApiKey()
        }

        fun fetchApiKey(): String? = fetchApiKeyFor(getActiveProvider())

        fun fetchApiKeyFor(provider: LlmProvider): String? = securePrefs?.getString(apiKeyPrefKey(provider), null)

        fun getModelName(): String = getModelFor(getActiveProvider())

        fun getModelFor(provider: LlmProvider): String =
            resolveModelId(
                provider = provider,
                storedModelId = appPrefs.getString(modelPrefKey(provider), null),
            )

        fun setModelFor(
            provider: LlmProvider,
            modelId: String,
        ) {
            appPrefs
                .edit()
                .putString(modelPrefKey(provider), resolveModelId(provider, modelId))
                .apply()
        }

        /** @deprecated Prefer [setModelFor]. */
        fun setModelName(modelName: String) {
            setModelFor(getActiveProvider(), modelName)
        }

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

        suspend fun setApiKey(key: String) {
            setApiKeyFor(getActiveProvider(), key)
        }

        suspend fun setApiKeyFor(
            provider: LlmProvider,
            key: String,
        ) {
            val normalized = key.trim()
            val prefs = securePrefs
            if (prefs == null) {
                Log.e(TAG, "Cannot save API key: secure storage unavailable")
                return
            }

            val committed = prefs.edit().putString(apiKeyPrefKey(provider), normalized).commit()
            if (committed) {
                if (provider == getActiveProvider()) {
                    _apiKey.value = normalized
                }
            } else {
                Log.e(TAG, "Failed to commit API key to secure storage")
            }
        }

        suspend fun clearApiKey() {
            clearApiKeyFor(getActiveProvider())
        }

        suspend fun clearApiKeyFor(provider: LlmProvider) {
            val prefs = securePrefs ?: return
            if (prefs.edit().remove(apiKeyPrefKey(provider)).commit() && provider == getActiveProvider()) {
                _apiKey.value = null
            }
        }

        fun hasApiKey(): Boolean = hasApiKeyFor(getActiveProvider())

        fun hasApiKeyFor(provider: LlmProvider): Boolean = !fetchApiKeyFor(provider).isNullOrBlank()

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

        suspend fun getAutoTitle(): Boolean = autoTitle.first()

        suspend fun getAutoSummary(): Boolean = autoSummary.first()

        suspend fun getLlmEnabled(): Boolean = llmEnabled.first()

        fun buildSettingsSnapshot(): LlmSettingsSnapshot {
            val apiKeys =
                LlmProvider.entries.mapNotNull { provider ->
                    fetchApiKeyFor(provider)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { provider.id to it }
                }.toMap()

            val models =
                LlmProvider.entries.associate { provider ->
                    provider.id to getModelFor(provider)
                }

            return LlmSettingsSnapshot(
                activeProvider = getActiveProvider().id,
                models = models,
                apiKeys = apiKeys,
            )
        }

        suspend fun applySettingsSnapshot(snapshot: LlmSettingsSnapshot) {
            val provider = LlmProvider.entries.firstOrNull { it.id == snapshot.activeProvider } ?: LlmProvider.GEMINI
            setActiveProvider(provider)

            snapshot.models.forEach { (providerId, modelId) ->
                val snapshotProvider = LlmProvider.entries.firstOrNull { it.id == providerId } ?: return@forEach
                setModelFor(snapshotProvider, modelId)
            }

            snapshot.apiKeys.forEach { (providerId, apiKey) ->
                val snapshotProvider = LlmProvider.entries.firstOrNull { it.id == providerId } ?: return@forEach
                if (apiKey.isNotBlank()) {
                    setApiKeyFor(snapshotProvider, apiKey)
                }
            }

            refreshActiveApiKey()
        }

        fun countConfiguredApiKeys(): Int =
            LlmProvider.entries.count { hasApiKeyFor(it) }

        private fun refreshActiveApiKey() {
            _apiKey.value = fetchApiKeyFor(getActiveProvider())
        }

        private fun migrateLegacyGeminiSettingsIfNeeded() {
            val legacyKey = securePrefs?.getString(LEGACY_GEMINI_CREDENTIAL_PREF, null)
            if (!legacyKey.isNullOrBlank() && fetchApiKeyFor(LlmProvider.GEMINI).isNullOrBlank()) {
                securePrefs?.edit()?.putString(apiKeyPrefKey(LlmProvider.GEMINI), legacyKey.trim())?.commit()
                securePrefs?.edit()?.remove(LEGACY_GEMINI_CREDENTIAL_PREF)?.commit()
            }

            val legacyModel = appPrefs.getString(LEGACY_GEMINI_MODEL_PREF, null)
            if (!legacyModel.isNullOrBlank() && appPrefs.getString(modelPrefKey(LlmProvider.GEMINI), null) == null) {
                appPrefs
                    .edit()
                    .putString(modelPrefKey(LlmProvider.GEMINI), resolveModelId(LlmProvider.GEMINI, legacyModel))
                    .apply()
            }

            if (appPrefs.getString(KEY_ACTIVE_PROVIDER, null) == null) {
                appPrefs.edit().putString(KEY_ACTIVE_PROVIDER, LlmProvider.GEMINI.id).apply()
            }
        }

        private fun migrateStoredModelNamesIfNeeded() {
            LlmProvider.entries.forEach { provider ->
                val stored = appPrefs.getString(modelPrefKey(provider), null) ?: return@forEach
                val resolved = resolveModelId(provider, stored)
                if (stored != resolved) {
                    Log.i(TAG, "Migrating ${provider.displayName} model from $stored to $resolved")
                    appPrefs.edit().putString(modelPrefKey(provider), resolved).apply()
                }
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
    }
