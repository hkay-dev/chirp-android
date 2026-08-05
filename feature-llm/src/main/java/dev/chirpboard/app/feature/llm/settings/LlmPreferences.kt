package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "llm_settings",
    // A corrupted preferences file would otherwise throw CorruptionException on every
    // read forever; resetting to defaults is strictly better. API keys live in
    // EncryptedSharedPreferences, not here, so nothing sensitive is lost.
    corruptionHandler =
        ReplaceFileCorruptionHandler { corruption ->
            Log.e("LlmPreferences", "llm_settings corrupted; resetting to defaults", corruption)
            emptyPreferences()
        },
)

interface LlmSettingsStore {
    suspend fun getLlmEnabled(): Boolean

    suspend fun setLlmEnabled(enabled: Boolean)

    fun getActiveProvider(): LlmProvider

    fun setActiveProvider(provider: LlmProvider)

    fun fetchApiKeyFor(provider: LlmProvider): String?

    fun getModelFor(provider: LlmProvider): String

    fun setModelFor(
        provider: LlmProvider,
        modelId: String,
    )

    fun hasApiKeyFor(provider: LlmProvider): Boolean

    fun countConfiguredApiKeys(): Int

    fun isSecureStorageAvailable(): Boolean

    /**
     * One-shot SEC-2 notice: true when the secure store was wiped and recreated because its
     * keyset became undecryptable — the UI should ask the user to re-enter their API keys.
     */
    fun consumeSecureStorageResetNotice(): Boolean

    fun setApiKeyFor(
        provider: LlmProvider,
        apiKey: String,
    )

    fun clearApiKeyFor(provider: LlmProvider)

    suspend fun getAutoTitle(): Boolean

    suspend fun setAutoTitle(enabled: Boolean)

    suspend fun getAutoSummary(): Boolean

    suspend fun setAutoSummary(enabled: Boolean)
}

@Singleton
class LlmPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LlmSettingsStore {
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
            private const val KEY_SECURE_STORE_RESET_PENDING = "secure_store_reset_pending"

            private fun apiKeyPrefKey(provider: LlmProvider): String = "llm_api_key_${provider.id}"

            private fun modelPrefKey(provider: LlmProvider): String = "llm_model_${provider.id}"
        }

        private val appPrefs: SharedPreferences by lazy {
            context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        }

        private val securePrefs: SharedPreferences? by lazy { createSecurePrefs() }

        private val _activeProvider = MutableStateFlow(LlmProvider.GEMINI)
        private val _apiKey = MutableStateFlow<String?>(null)

        /**
         * One-time legacy migration + active-provider/api-key priming. Deliberately NOT run from
         * an `init {}` block: that block dereferences [securePrefs], which builds an Android
         * Keystore master key and opens [EncryptedSharedPreferences] (commonly 100-500 ms,
         * occasionally seconds after boot) plus a synchronous SharedPreferences disk load. This
         * type is constructed during Hilt member injection on the main thread at process start
         * (every cold keyboard show in the shared IME process), so doing it in the constructor
         * blocked the app's hottest path. Instead it runs once, lazily, on first access of any
         * public read/write — by which point the caller is already off the critical onCreate
         * path. The guard is idempotent and thread-safe.
         */
        @Volatile
        private var initialized = false
        private val initLock = Any()

        private fun ensureInitialized() {
            if (initialized) return
            synchronized(initLock) {
                if (initialized) return
                migrateLegacyGeminiSettingsIfNeeded()
                migrateStoredModelNamesIfNeeded()
                _activeProvider.value = readActiveProvider()
                refreshActiveApiKey()
                initialized = true
            }
        }

        val llmEnabled: Flow<Boolean> =
            context.dataStore.data.map { preferences ->
                preferences[Keys.LLM_ENABLED] ?: true
            }

        val activeProvider: Flow<LlmProvider> = _activeProvider.asStateFlow()

        /** API key for the currently selected provider. */
        val apiKey: Flow<String?> = _apiKey.asStateFlow()

        override fun getActiveProvider(): LlmProvider {
            ensureInitialized()
            return readActiveProvider()
        }

        /** Reads the stored active provider without triggering [ensureInitialized] (used during init). */
        private fun readActiveProvider(): LlmProvider = LlmProvider.fromId(appPrefs.getString(KEY_ACTIVE_PROVIDER, null))

        override fun setActiveProvider(provider: LlmProvider) {
            ensureInitialized()
            appPrefs.edit().putString(KEY_ACTIVE_PROVIDER, provider.id).apply()
            _activeProvider.value = provider
            refreshActiveApiKey()
        }

        fun fetchApiKey(): String? = fetchApiKeyFor(getActiveProvider())

        override fun fetchApiKeyFor(provider: LlmProvider): String? {
            ensureInitialized()
            return fetchApiKeyForRaw(provider)
        }

        /** Reads a stored API key without triggering [ensureInitialized] (used during init). */
        private fun fetchApiKeyForRaw(provider: LlmProvider): String? = securePrefs?.getString(apiKeyPrefKey(provider), null)

        fun getModelName(): String = getModelFor(getActiveProvider())

        override fun getModelFor(provider: LlmProvider): String {
            ensureInitialized()
            return resolveModelId(
                provider = provider,
                storedModelId = appPrefs.getString(modelPrefKey(provider), null),
            )
        }

        override fun setModelFor(
            provider: LlmProvider,
            modelId: String,
        ) {
            ensureInitialized()
            appPrefs
                .edit()
                .putString(modelPrefKey(provider), resolveModelId(provider, modelId))
                .apply()
        }

        val autoTitle: Flow<Boolean> =
            context.dataStore.data.map { preferences ->
                preferences[Keys.AUTO_TITLE] ?: false
            }

        val autoSummary: Flow<Boolean> =
            context.dataStore.data.map { preferences ->
                preferences[Keys.AUTO_SUMMARY] ?: false
            }

        override suspend fun setLlmEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[Keys.LLM_ENABLED] = enabled
            }
        }

        fun setApiKey(key: String) {
            setApiKeyFor(getActiveProvider(), key)
        }

        override fun setApiKeyFor(
            provider: LlmProvider,
            key: String,
        ) {
            ensureInitialized()
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

        fun clearApiKey() {
            clearApiKeyFor(getActiveProvider())
        }

        override fun clearApiKeyFor(provider: LlmProvider) {
            ensureInitialized()
            val prefs = securePrefs ?: return
            if (prefs.edit().remove(apiKeyPrefKey(provider)).commit() && provider == getActiveProvider()) {
                _apiKey.value = null
            }
        }

        fun hasApiKey(): Boolean = hasApiKeyFor(getActiveProvider())

        override fun hasApiKeyFor(provider: LlmProvider): Boolean = !fetchApiKeyFor(provider).isNullOrBlank()

        override fun isSecureStorageAvailable(): Boolean {
            ensureInitialized()
            return securePrefs != null
        }

        override suspend fun setAutoTitle(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[Keys.AUTO_TITLE] = enabled
            }
        }

        override suspend fun setAutoSummary(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[Keys.AUTO_SUMMARY] = enabled
            }
        }

        override suspend fun getAutoTitle(): Boolean = autoTitle.first()

        override suspend fun getAutoSummary(): Boolean = autoSummary.first()

        override suspend fun getLlmEnabled(): Boolean = llmEnabled.first()

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

        override fun countConfiguredApiKeys(): Int =
            LlmProvider.entries.count { hasApiKeyFor(it) }

        private fun refreshActiveApiKey() {
            // Uses raw reads so it is safe to call from inside ensureInitialized() (where the
            // initialized guard is not yet set) as well as from already-initialized callers.
            _apiKey.value = fetchApiKeyForRaw(readActiveProvider())
        }

        private fun migrateLegacyGeminiSettingsIfNeeded() {
            val legacyKey = securePrefs?.getString(LEGACY_GEMINI_CREDENTIAL_PREF, null)
            if (!legacyKey.isNullOrBlank() && fetchApiKeyForRaw(LlmProvider.GEMINI).isNullOrBlank()) {
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

        /**
         * SEC-2: opens the secure store, self-healing the well-known EncryptedSharedPreferences
         * "undecryptable keyset" trap. If the AndroidKeyStore master key was invalidated while
         * secure_prefs.xml stayed on disk, create() throws on EVERY call forever — which used to
         * leave [securePrefs] permanently null, so API keys could never be read OR saved again
         * without Clear Data. Now the poisoned file is deleted and the store recreated once:
         * the stored keys are lost (they were already undecryptable), the user re-enters them,
         * and a one-time notice is queued via [consumeSecureStorageResetNotice].
         */
        private fun createSecurePrefs(): SharedPreferences? {
            val firstAttempt =
                runCatching { openSecurePrefs() }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Log.e(TAG, "Failed to create EncryptedSharedPreferences; resetting store", error)
                    }
            firstAttempt.getOrNull()?.let { return it }

            // Wipe the undecryptable store and retry once. deleteSharedPreferences removes the
            // backing XML (the Tink keyset + ciphertext that no longer match the Keystore key).
            return runCatching {
                if (!context.deleteSharedPreferences(SECURE_PREFS_NAME)) {
                    Log.w(TAG, "deleteSharedPreferences($SECURE_PREFS_NAME) reported failure")
                }
                val recreated = openSecurePrefs()
                markSecureStorageResetPending()
                Log.w(TAG, "Secure storage was reset after an undecryptable keyset; keys must be re-entered")
                recreated
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Secure storage unavailable even after reset", error)
            }.getOrNull()
        }

        private fun openSecurePrefs(): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun markSecureStorageResetPending() {
            // Plain (non-secure) prefs on purpose: the marker carries no secret and must be
            // readable even if the secure store breaks again.
            appPrefs.edit().putBoolean(KEY_SECURE_STORE_RESET_PENDING, true).apply()
        }

        /**
         * One-shot: true when the secure store had to be wiped and recreated (SEC-2), so the
         * settings UI can tell the user to re-enter their API keys. Clears the flag on read.
         */
        override fun consumeSecureStorageResetNotice(): Boolean {
            ensureInitialized()
            val pending = appPrefs.getBoolean(KEY_SECURE_STORE_RESET_PENDING, false)
            if (pending) {
                appPrefs.edit().remove(KEY_SECURE_STORE_RESET_PENDING).apply()
            }
            return pending
        }
    }
