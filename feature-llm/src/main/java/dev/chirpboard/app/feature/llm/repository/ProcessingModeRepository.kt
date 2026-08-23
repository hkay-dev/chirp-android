package dev.chirpboard.app.feature.llm.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import dev.chirpboard.app.feature.llm.model.ProcessingModeListItem
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "processing_mode_preferences",
    // A corrupted preferences file would otherwise throw CorruptionException on every
    // read forever; resetting to defaults is strictly better.
    corruptionHandler =
        ReplaceFileCorruptionHandler { corruption ->
            Log.e(
                "ProcessingModeRepo",
                "processing_mode_preferences corrupted; resetting to defaults",
                corruption,
            )
            emptyPreferences()
        },
)

internal data class StoredCustomPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val originalPrompt: String,
)

/**
 * Repository for processing mode selection and prompt preset management.
 */
@Singleton
class ProcessingModeRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * Single read path for the DataStore. The corruption handler only covers
         * CorruptionException; a plain IOException (unreadable file, no space, a device in
         * direct boot) otherwise propagates to every collector — killing the Prompt Settings
         * screen outright, and escaping the Result contract of the background prompt reads.
         * Fall back to defaults instead, matching what the corruption handler already does.
         */
        private val preferencesFlow: Flow<Preferences> =
            context.dataStore.data.catch { error ->
                if (error is IOException) {
                    Log.e(TAG, "Failed to read processing_mode_preferences; using defaults", error)
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }

        val currentMode: Flow<ProcessingMode> =
            preferencesFlow.map { preferences ->
                buildMode(
                    modeId = preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID,
                    preferences = preferences,
                )
            }

        val defaultModeId: Flow<String> =
            preferencesFlow.map { preferences ->
                preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID
            }

        val promptPresets: Flow<List<ProcessingPromptPreset>> =
            preferencesFlow.map { preferences ->
                buildPromptPresets(preferences)
            }

        val selectableModes: Flow<List<ProcessingModeListItem>> =
            preferencesFlow.map { preferences ->
                buildSelectableModes(preferences)
            }

        suspend fun getPrompt(modeId: String): String? {
            val preferences = preferencesFlow.first()
            return resolvePrompt(modeId, preferences)
        }

        suspend fun resolveMode(modeId: String): ProcessingMode {
            val preferences = preferencesFlow.first()
            return buildMode(modeId, preferences)
        }

        suspend fun setModeById(modeId: String) {
            context.dataStore.edit { preferences ->
                preferences[KEY_MODE_ID] = modeId
            }
        }

        suspend fun updatePresetPrompt(
            presetId: String,
            prompt: String,
        ) {
            val trimmed = prompt.trim()
            require(trimmed.isNotEmpty()) { "Prompt cannot be empty" }

            context.dataStore.edit { preferences ->
                val customPresets = readCustomPresets(preferences)
                if (customPresets.any { it.id == presetId }) {
                    val updated =
                        customPresets.map { preset ->
                            if (preset.id == presetId) preset.copy(prompt = trimmed) else preset
                        }
                    preferences[KEY_CUSTOM_PRESETS] = ProcessingModeStoreCodec.encodePresets(updated)
                } else {
                    require(ProcessingModeDefaults.isEditable(presetId)) { "Preset is not editable" }
                    val overrides = readOverrides(preferences).toMutableMap()
                    overrides[presetId] = trimmed
                    preferences[KEY_PROMPT_OVERRIDES] = ProcessingModeStoreCodec.encodeOverrides(overrides)
                }
            }
        }

        suspend fun resetPresetPrompt(presetId: String) {
            context.dataStore.edit { preferences ->
                val customPresets = readCustomPresets(preferences)
                val customPreset = customPresets.find { it.id == presetId }
                if (customPreset != null) {
                    val updated =
                        customPresets.map { preset ->
                            if (preset.id == presetId) {
                                preset.copy(prompt = preset.originalPrompt)
                            } else {
                                preset
                            }
                        }
                    preferences[KEY_CUSTOM_PRESETS] = ProcessingModeStoreCodec.encodePresets(updated)
                    val overrides = readOverrides(preferences).toMutableMap()
                    overrides.remove(presetId)
                    preferences[KEY_PROMPT_OVERRIDES] = ProcessingModeStoreCodec.encodeOverrides(overrides)
                    return@edit
                }

                val overrides = readOverrides(preferences).toMutableMap()
                overrides.remove(presetId)
                preferences[KEY_PROMPT_OVERRIDES] = ProcessingModeStoreCodec.encodeOverrides(overrides)
            }
        }

        suspend fun addCustomPreset(
            name: String,
            prompt: String,
        ): String {
            val trimmedName = name.trim()
            val trimmedPrompt = prompt.trim()
            require(trimmedName.isNotEmpty()) { "Preset name cannot be empty" }
            require(trimmedPrompt.isNotEmpty()) { "Prompt cannot be empty" }

            val presetId = "user_${UUID.randomUUID()}"
            val stored =
                StoredCustomPreset(
                    id = presetId,
                    name = trimmedName,
                    prompt = trimmedPrompt,
                    originalPrompt = trimmedPrompt,
                )

            context.dataStore.edit { preferences ->
                val customPresets = readCustomPresets(preferences).toMutableList()
                customPresets.add(stored)
                preferences[KEY_CUSTOM_PRESETS] = ProcessingModeStoreCodec.encodePresets(customPresets)
            }

            return presetId
        }

        suspend fun renameCustomPreset(
            presetId: String,
            name: String,
        ) {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "Preset name cannot be empty" }

            context.dataStore.edit { preferences ->
                val customPresets = readCustomPresets(preferences)
                require(customPresets.any { it.id == presetId }) { "Preset not found" }
                val updated =
                    customPresets.map { preset ->
                        if (preset.id == presetId) preset.copy(name = trimmedName) else preset
                    }
                preferences[KEY_CUSTOM_PRESETS] = ProcessingModeStoreCodec.encodePresets(updated)
            }
        }

        suspend fun deleteCustomPreset(presetId: String) {
            context.dataStore.edit { preferences ->
                val customPresets = readCustomPresets(preferences).filterNot { it.id == presetId }
                preferences[KEY_CUSTOM_PRESETS] = ProcessingModeStoreCodec.encodePresets(customPresets)

                val overrides = readOverrides(preferences).toMutableMap()
                overrides.remove(presetId)
                preferences[KEY_PROMPT_OVERRIDES] = ProcessingModeStoreCodec.encodeOverrides(overrides)

                val currentModeId = preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID
                if (currentModeId == presetId) {
                    preferences[KEY_MODE_ID] = ProcessingModeDefaults.DEFAULT_MODE_ID
                }
            }
        }

        /**
         * Applies a backup's presets ATOMICALLY: the final custom-preset list and override
         * map are planned in memory ([planProcessingPresetBackup]) and committed in ONE
         * DataStore edit. Backup restore must use this instead of looping the public
         * single-preset setters — each of those is its own commit, so a failure or
         * cancellation between commits would leave presets half-restored (e.g. customs
         * deleted but not re-inserted), violating the import UI's "existing data was left
         * unchanged" failure contract. Throws (and commits nothing) on invalid items.
         */
        suspend fun applyBackupPresets(
            items: List<ProcessingPresetBackupItem>,
            replaceExisting: Boolean,
        ): ProcessingPresetBackupResult {
            var result: ProcessingPresetBackupResult? = null
            context.dataStore.edit { preferences ->
                val plan =
                    planProcessingPresetBackup(
                        existingCustomPresets = readCustomPresets(preferences),
                        existingOverrides = readOverrides(preferences),
                        currentModeId = preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID,
                        items = items,
                        replaceExisting = replaceExisting,
                    )
                preferences[KEY_CUSTOM_PRESETS] = ProcessingModeStoreCodec.encodePresets(plan.customPresets)
                preferences[KEY_PROMPT_OVERRIDES] = ProcessingModeStoreCodec.encodeOverrides(plan.overrides)
                if (plan.resetModeToDefault) {
                    preferences[KEY_MODE_ID] = ProcessingModeDefaults.DEFAULT_MODE_ID
                }
                result = plan.result
            }
            return requireNotNull(result) { "DataStore edit completed without running the transform" }
        }

        private fun buildMode(
            modeId: String,
            preferences: Preferences,
        ): ProcessingMode {
            // Decode the stored JSON blobs once; readCustomPresets/readOverrides parse on
            // every call, and this runs on each DataStore emission.
            val customPresets = readCustomPresets(preferences)
            val overrides = readOverrides(preferences)
            val customPreset = customPresets.find { it.id == modeId }
            return when {
                modeId == "smart" -> ProcessingMode.Smart
                modeId == "custom" ->
                    ProcessingMode.Custom(resolvePrompt("custom", preferences, customPresets, overrides).orEmpty())

                customPreset != null ->
                    ProcessingMode.UserPreset(
                        presetId = customPreset.id,
                        name = customPreset.name,
                        promptText = resolvePrompt(modeId, preferences, customPresets, overrides).orEmpty(),
                    )

                else -> {
                    val base = ProcessingMode.fromId(modeId)
                    if (base.prompt == null) {
                        base
                    } else {
                        ProcessingMode.UserPreset(
                            presetId = base.id,
                            name = base.displayName,
                            promptText = resolvePrompt(modeId, preferences, customPresets, overrides).orEmpty(),
                        )
                    }
                }
            }
        }

        private fun buildPromptPresets(preferences: Preferences): List<ProcessingPromptPreset> {
            // One decode of each blob per emission instead of one per resolvePrompt call.
            val overrides = readOverrides(preferences)
            val customPresets = readCustomPresets(preferences)
            val builtIn =
                ProcessingModeDefaults.builtInSelectableIds.map { modeId ->
                    val originalPrompt = ProcessingModeDefaults.defaultPrompt(modeId)
                    val effectivePrompt = resolvePrompt(modeId, preferences, customPresets, overrides)
                    ProcessingPromptPreset(
                        id = modeId,
                        name = ProcessingModeDefaults.displayName(modeId),
                        prompt = effectivePrompt,
                        originalPrompt = originalPrompt,
                        isBuiltIn = true,
                        isModified =
                            when {
                                modeId == "smart" -> false
                                originalPrompt == null -> false
                                else -> effectivePrompt != originalPrompt
                            },
                        canEditPrompt = ProcessingModeDefaults.isEditable(modeId),
                    )
                }

            val custom =
                customPresets.map { preset ->
                    val effectivePrompt = resolvePrompt(preset.id, preferences, customPresets, overrides)
                    ProcessingPromptPreset(
                        id = preset.id,
                        name = preset.name,
                        prompt = effectivePrompt,
                        originalPrompt = preset.originalPrompt,
                        isBuiltIn = false,
                        isModified = effectivePrompt != preset.originalPrompt,
                        canEditPrompt = true,
                    )
                }

            return builtIn + custom
        }

        private fun buildSelectableModes(preferences: Preferences): List<ProcessingModeListItem> {
            val builtIn =
                ProcessingModeDefaults.builtInSelectableIds.map { modeId ->
                    ProcessingModeListItem(
                        id = modeId,
                        name = ProcessingModeDefaults.displayName(modeId),
                    )
                }
            val custom =
                readCustomPresets(preferences).map { preset ->
                    ProcessingModeListItem(id = preset.id, name = preset.name)
                }
            return builtIn + custom
        }

        private fun resolvePrompt(
            modeId: String,
            preferences: Preferences,
            customPresets: List<StoredCustomPreset> = readCustomPresets(preferences),
            overrides: Map<String, String> = readOverrides(preferences),
        ): String? {
            if (modeId == "smart") return null

            customPresets.find { it.id == modeId }?.let { preset ->
                return overrides[modeId] ?: preset.prompt
            }

            if (modeId == "custom") {
                return overrides["custom"]
                    ?: preferences[KEY_CUSTOM_PROMPT]
                    ?: ""
            }

            val defaultPrompt = ProcessingModeDefaults.defaultPrompt(modeId) ?: return null
            return overrides[modeId] ?: defaultPrompt
        }

        // DAT-013: reads go through the versioned, null-validating codec. Both functions accept
        // the legacy unversioned blob shape; the next write persists the versioned envelope.
        private fun readOverrides(preferences: Preferences): Map<String, String> {
            val json = preferences[KEY_PROMPT_OVERRIDES] ?: return emptyMap()
            return ProcessingModeStoreCodec.decodeOverrides(json)
        }

        private fun readCustomPresets(preferences: Preferences): List<StoredCustomPreset> {
            val json = preferences[KEY_CUSTOM_PRESETS] ?: return emptyList()
            return ProcessingModeStoreCodec.decodePresets(json)
        }

        companion object {
            private const val TAG = "ProcessingModeRepo"
            private val KEY_MODE_ID = stringPreferencesKey("mode_id")
            private val KEY_CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
            private val KEY_PROMPT_OVERRIDES = stringPreferencesKey("prompt_overrides_json")
            private val KEY_CUSTOM_PRESETS = stringPreferencesKey("custom_presets_json")
        }
    }
