package dev.chirpboard.app.feature.llm.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import dev.chirpboard.app.feature.llm.model.ProcessingModeListItem
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "processing_mode_preferences",
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
        private val gson = Gson()

        val currentMode: Flow<ProcessingMode> =
            context.dataStore.data.map { preferences ->
                buildMode(
                    modeId = preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID,
                    preferences = preferences,
                )
            }

        val currentModeId: Flow<String> =
            context.dataStore.data.map { preferences ->
                preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID
            }

        val defaultModeId: Flow<String> =
            context.dataStore.data.map { preferences ->
                preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID
            }

        val customPrompt: Flow<String> =
            context.dataStore.data.map { preferences ->
                preferences[KEY_CUSTOM_PROMPT] ?: ""
            }

        val promptPresets: Flow<List<ProcessingPromptPreset>> =
            context.dataStore.data.map { preferences ->
                buildPromptPresets(preferences)
            }

        val selectableModes: Flow<List<ProcessingModeListItem>> =
            context.dataStore.data.map { preferences ->
                buildSelectableModes(preferences)
            }

        suspend fun getPrompt(modeId: String): String? {
            val preferences = context.dataStore.data.first()
            return resolvePrompt(modeId, preferences)
        }

        suspend fun resolveMode(modeId: String): ProcessingMode {
            val preferences = context.dataStore.data.first()
            return buildMode(modeId, preferences)
        }

        suspend fun setMode(mode: ProcessingMode) {
            setModeById(mode.id)
        }

        suspend fun setModeById(modeId: String) {
            context.dataStore.edit { preferences ->
                preferences[KEY_MODE_ID] = modeId
                if (modeId == "custom") {
                    preferences[KEY_CUSTOM_PROMPT] = resolvePrompt("custom", preferences).orEmpty()
                }
            }
        }

        suspend fun setDefaultModeId(modeId: String) {
            setModeById(modeId)
        }

        suspend fun setCustomPrompt(prompt: String) {
            context.dataStore.edit { preferences ->
                preferences[KEY_CUSTOM_PROMPT] = prompt
                preferences[KEY_MODE_ID] = "custom"
                val overrides = readOverrides(preferences).toMutableMap()
                overrides["custom"] = prompt
                preferences[KEY_PROMPT_OVERRIDES] = gson.toJson(overrides)
            }
        }

        suspend fun saveCustomPromptDraft(prompt: String) {
            context.dataStore.edit { preferences ->
                preferences[KEY_CUSTOM_PROMPT] = prompt
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
                    preferences[KEY_CUSTOM_PRESETS] = gson.toJson(updated)
                } else {
                    require(ProcessingModeDefaults.isEditable(presetId)) { "Preset is not editable" }
                    val overrides = readOverrides(preferences).toMutableMap()
                    overrides[presetId] = trimmed
                    preferences[KEY_PROMPT_OVERRIDES] = gson.toJson(overrides)
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
                    preferences[KEY_CUSTOM_PRESETS] = gson.toJson(updated)
                    val overrides = readOverrides(preferences).toMutableMap()
                    overrides.remove(presetId)
                    preferences[KEY_PROMPT_OVERRIDES] = gson.toJson(overrides)
                    return@edit
                }

                if (presetId == "custom") {
                    preferences.remove(KEY_CUSTOM_PROMPT)
                }

                val overrides = readOverrides(preferences).toMutableMap()
                overrides.remove(presetId)
                preferences[KEY_PROMPT_OVERRIDES] = gson.toJson(overrides)
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
                preferences[KEY_CUSTOM_PRESETS] = gson.toJson(customPresets)
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
                preferences[KEY_CUSTOM_PRESETS] = gson.toJson(updated)
            }
        }

        suspend fun deleteCustomPreset(presetId: String) {
            context.dataStore.edit { preferences ->
                val customPresets = readCustomPresets(preferences).filterNot { it.id == presetId }
                preferences[KEY_CUSTOM_PRESETS] = gson.toJson(customPresets)

                val overrides = readOverrides(preferences).toMutableMap()
                overrides.remove(presetId)
                preferences[KEY_PROMPT_OVERRIDES] = gson.toJson(overrides)

                val currentModeId = preferences[KEY_MODE_ID] ?: ProcessingModeDefaults.DEFAULT_MODE_ID
                if (currentModeId == presetId) {
                    preferences[KEY_MODE_ID] = ProcessingModeDefaults.DEFAULT_MODE_ID
                }
            }
        }

        suspend fun reset() {
            context.dataStore.edit { preferences ->
                preferences[KEY_MODE_ID] = ProcessingModeDefaults.DEFAULT_MODE_ID
                preferences.remove(KEY_CUSTOM_PROMPT)
                preferences.remove(KEY_PROMPT_OVERRIDES)
                preferences.remove(KEY_CUSTOM_PRESETS)
            }
        }

        private fun buildMode(
            modeId: String,
            preferences: Preferences,
        ): ProcessingMode =
            when {
                modeId == "smart" -> ProcessingMode.Smart
                modeId == "custom" -> ProcessingMode.Custom(resolvePrompt("custom", preferences).orEmpty())
                readCustomPresets(preferences).any { it.id == modeId } -> {
                    val preset = readCustomPresets(preferences).first { it.id == modeId }
                    ProcessingMode.UserPreset(
                        presetId = preset.id,
                        name = preset.name,
                        promptText = resolvePrompt(modeId, preferences).orEmpty(),
                    )
                }

                else -> {
                    val base = ProcessingMode.fromId(modeId)
                    if (base.prompt == null) {
                        base
                    } else {
                        ProcessingMode.UserPreset(
                            presetId = base.id,
                            name = base.displayName,
                            promptText = resolvePrompt(modeId, preferences).orEmpty(),
                        )
                    }
                }
            }

        private fun buildPromptPresets(preferences: Preferences): List<ProcessingPromptPreset> {
            val overrides = readOverrides(preferences)
            val builtIn =
                ProcessingModeDefaults.builtInSelectableIds.map { modeId ->
                    val originalPrompt = ProcessingModeDefaults.defaultPrompt(modeId)
                    val effectivePrompt = resolvePrompt(modeId, preferences)
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
                readCustomPresets(preferences).map { preset ->
                    val effectivePrompt = resolvePrompt(preset.id, preferences)
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
        ): String? {
            if (modeId == "smart") return null

            readCustomPresets(preferences).find { it.id == modeId }?.let { preset ->
                return readOverrides(preferences)[modeId] ?: preset.prompt
            }

            if (modeId == "custom") {
                return readOverrides(preferences)["custom"]
                    ?: preferences[KEY_CUSTOM_PROMPT]
                    ?: ""
            }

            val defaultPrompt = ProcessingModeDefaults.defaultPrompt(modeId) ?: return null
            return readOverrides(preferences)[modeId] ?: defaultPrompt
        }

        private fun readOverrides(preferences: Preferences): Map<String, String> {
            val json = preferences[KEY_PROMPT_OVERRIDES] ?: return emptyMap()
            return runCatching {
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
            }.getOrDefault(emptyMap())
        }

        private fun readCustomPresets(preferences: Preferences): List<StoredCustomPreset> {
            val json = preferences[KEY_CUSTOM_PRESETS] ?: return emptyList()
            return runCatching {
                val type = object : TypeToken<List<StoredCustomPreset>>() {}.type
                gson.fromJson<List<StoredCustomPreset>>(json, type) ?: emptyList()
            }.getOrDefault(emptyList())
        }

        companion object {
            private val KEY_MODE_ID = stringPreferencesKey("mode_id")
            private val KEY_CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
            private val KEY_PROMPT_OVERRIDES = stringPreferencesKey("prompt_overrides_json")
            private val KEY_CUSTOM_PRESETS = stringPreferencesKey("custom_presets_json")
        }
    }
