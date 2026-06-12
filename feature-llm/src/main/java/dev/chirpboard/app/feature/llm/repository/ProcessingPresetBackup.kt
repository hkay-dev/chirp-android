package dev.chirpboard.app.feature.llm.repository

import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import java.util.UUID

/** One processing preset carried by a backup file, to be applied via [ProcessingModeRepository.applyBackupPresets]. */
data class ProcessingPresetBackupItem(
    val id: String,
    val name: String,
    val prompt: String,
    val builtIn: Boolean,
)

/** Outcome of applying a backup's presets: counts plus the source-id → applied-id remap. */
data class ProcessingPresetBackupResult(
    val inserted: Int,
    val updated: Int,
    val idRemap: Map<String, String>,
)

/**
 * The complete post-apply preset state, computed in memory so the repository can commit it
 * in ONE DataStore edit. Restores used to run one commit per delete/insert/update, which
 * left the store half-applied (custom presets already deleted, nothing re-inserted) when a
 * later commit failed or the restore was cancelled mid-way.
 */
internal data class ProcessingPresetBackupPlan(
    val customPresets: List<StoredCustomPreset>,
    val overrides: Map<String, String>,
    /** True when the current mode pointed at a custom preset the REPLACE pass deleted. */
    val resetModeToDefault: Boolean,
    val result: ProcessingPresetBackupResult,
)

/**
 * Pure planning step for a preset restore; mirrors the public-setter semantics exactly:
 *
 * - REPLACE first drops every custom preset (with its override, and the mode selection if
 *   it pointed there — like [ProcessingModeRepository.deleteCustomPreset]) and resets the
 *   editable built-ins' prompt overrides (like [ProcessingModeRepository.resetPresetPrompt];
 *   overrides of non-editable or unknown ids are left untouched).
 * - Built-in items apply as prompt overrides, only for editable built-in ids — unknown or
 *   non-editable ids are skipped because they cannot be applied on this app version and
 *   must not fail the section.
 * - Custom items match by NAME against the pre-apply state: a match updates the prompt in
 *   place (id kept), otherwise a new preset is inserted under a fresh id.
 *
 * Validation failures throw [IllegalArgumentException] BEFORE anything is committed, so a
 * rejected backup leaves every existing preset untouched.
 */
internal fun planProcessingPresetBackup(
    existingCustomPresets: List<StoredCustomPreset>,
    existingOverrides: Map<String, String>,
    currentModeId: String,
    items: List<ProcessingPresetBackupItem>,
    replaceExisting: Boolean,
    newPresetId: () -> String = { "user_${UUID.randomUUID()}" },
): ProcessingPresetBackupPlan {
    val overrides = existingOverrides.toMutableMap()
    var resetModeToDefault = false
    val baseCustomPresets =
        if (replaceExisting) {
            existingCustomPresets.forEach { overrides.remove(it.id) }
            ProcessingModeDefaults.editableBuiltInIds.forEach(overrides::remove)
            resetModeToDefault = existingCustomPresets.any { it.id == currentModeId }
            emptyList()
        } else {
            existingCustomPresets
        }

    val customPresets = baseCustomPresets.toMutableList()
    val idRemap = mutableMapOf<String, String>()
    var inserted = 0
    var updated = 0
    for (item in items) {
        if (item.builtIn) {
            if (ProcessingModeDefaults.isBuiltIn(item.id) && ProcessingModeDefaults.isEditable(item.id)) {
                overrides[item.id] = requireNonBlankPrompt(item)
                idRemap[item.id] = item.id
                updated++
            }
        } else {
            // Matched against the PRE-apply state, like the old per-call restore did.
            val match = baseCustomPresets.firstOrNull { it.name == item.name }
            if (match != null) {
                val trimmedPrompt = requireNonBlankPrompt(item)
                val index = customPresets.indexOfFirst { it.id == match.id }
                customPresets[index] = customPresets[index].copy(prompt = trimmedPrompt)
                idRemap[item.id] = match.id
                updated++
            } else {
                val trimmedName = item.name.trim()
                require(trimmedName.isNotEmpty()) { "Preset name cannot be empty" }
                val trimmedPrompt = requireNonBlankPrompt(item)
                val presetId = newPresetId()
                customPresets +=
                    StoredCustomPreset(
                        id = presetId,
                        name = trimmedName,
                        prompt = trimmedPrompt,
                        originalPrompt = trimmedPrompt,
                    )
                idRemap[item.id] = presetId
                inserted++
            }
        }
    }

    return ProcessingPresetBackupPlan(
        customPresets = customPresets,
        overrides = overrides,
        resetModeToDefault = resetModeToDefault,
        result = ProcessingPresetBackupResult(inserted = inserted, updated = updated, idRemap = idRemap),
    )
}

private fun requireNonBlankPrompt(item: ProcessingPresetBackupItem): String {
    val trimmed = item.prompt.trim()
    require(trimmed.isNotEmpty()) { "Prompt cannot be empty" }
    return trimmed
}
