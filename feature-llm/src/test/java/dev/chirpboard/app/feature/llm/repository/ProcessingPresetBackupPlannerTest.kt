package dev.chirpboard.app.feature.llm.repository

import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure planning semantics behind [ProcessingModeRepository.applyBackupPresets]. The plan is
 * committed in ONE DataStore edit, so everything asserted here is all-or-nothing on device:
 * a thrown validation error means nothing was changed.
 */
class ProcessingPresetBackupPlannerTest {
    private fun custom(
        id: String,
        name: String,
        prompt: String = "prompt of $name",
    ): StoredCustomPreset = StoredCustomPreset(id = id, name = name, prompt = prompt, originalPrompt = prompt)

    private fun item(
        id: String,
        name: String,
        prompt: String = "imported prompt",
        builtIn: Boolean = false,
    ): ProcessingPresetBackupItem = ProcessingPresetBackupItem(id = id, name = name, prompt = prompt, builtIn = builtIn)

    @Test
    fun `merge updates an existing custom preset matched by name and keeps its id`() {
        val plan =
            planProcessingPresetBackup(
                existingCustomPresets = listOf(custom("user_local", "Standup", prompt = "old prompt")),
                existingOverrides = emptyMap(),
                currentModeId = "user_local",
                items = listOf(item("user_src", "Standup", prompt = "new prompt")),
                replaceExisting = false,
            )

        val updated = plan.customPresets.single()
        assertEquals("user_local", updated.id)
        assertEquals("new prompt", updated.prompt)
        assertEquals("old prompt", updated.originalPrompt)
        assertEquals(mapOf("user_src" to "user_local"), plan.result.idRemap)
        assertEquals(1, plan.result.updated)
        assertEquals(0, plan.result.inserted)
        assertFalse(plan.resetModeToDefault)
    }

    @Test
    fun `merge inserts unmatched customs under a fresh id and remaps to it`() {
        val plan =
            planProcessingPresetBackup(
                existingCustomPresets = listOf(custom("user_keep", "Existing")),
                existingOverrides = emptyMap(),
                currentModeId = ProcessingModeDefaults.DEFAULT_MODE_ID,
                items = listOf(item("user_src", "Brand New", prompt = "  fresh prompt  ")),
                replaceExisting = false,
                newPresetId = { "user_generated" },
            )

        assertEquals(listOf("user_keep", "user_generated"), plan.customPresets.map { it.id })
        val inserted = plan.customPresets.last()
        assertEquals("Brand New", inserted.name)
        assertEquals("fresh prompt", inserted.prompt)
        assertEquals("fresh prompt", inserted.originalPrompt)
        assertEquals(mapOf("user_src" to "user_generated"), plan.result.idRemap)
        assertEquals(1, plan.result.inserted)
    }

    @Test
    fun `replace drops existing customs with their overrides and resets editable built-ins`() {
        val plan =
            planProcessingPresetBackup(
                existingCustomPresets = listOf(custom("user_gone", "Old Custom")),
                existingOverrides =
                    mapOf(
                        "user_gone" to "stale custom override",
                        "email" to "tweaked built-in",
                        "custom" to "freeform prompt kept",
                    ),
                currentModeId = ProcessingModeDefaults.DEFAULT_MODE_ID,
                items = listOf(item("user_i", "Imported", prompt = "p")),
                replaceExisting = true,
                newPresetId = { "user_new" },
            )

        // The wiped custom never matches by name; the import lands as a NEW preset.
        assertEquals(listOf("user_new"), plan.customPresets.map { it.id })
        assertFalse(plan.overrides.containsKey("user_gone"))
        assertFalse(plan.overrides.containsKey("email"))
        // Overrides outside the editable built-ins (the freeform "custom" prompt) survive,
        // matching the old per-preset reset behavior.
        assertEquals("freeform prompt kept", plan.overrides["custom"])
        assertEquals(1, plan.result.inserted)
    }

    @Test
    fun `replace resets the selected mode only when it pointed at a deleted custom`() {
        val deletedModePlan =
            planProcessingPresetBackup(
                existingCustomPresets = listOf(custom("user_gone", "Old")),
                existingOverrides = emptyMap(),
                currentModeId = "user_gone",
                items = emptyList(),
                replaceExisting = true,
            )
        assertTrue(deletedModePlan.resetModeToDefault)

        val builtInModePlan =
            planProcessingPresetBackup(
                existingCustomPresets = listOf(custom("user_gone", "Old")),
                existingOverrides = emptyMap(),
                currentModeId = "email",
                items = emptyList(),
                replaceExisting = true,
            )
        assertFalse(builtInModePlan.resetModeToDefault)
    }

    @Test
    fun `built-in items apply as overrides only for editable built-in ids`() {
        val plan =
            planProcessingPresetBackup(
                existingCustomPresets = emptyList(),
                existingOverrides = emptyMap(),
                currentModeId = ProcessingModeDefaults.DEFAULT_MODE_ID,
                items =
                    listOf(
                        item("email", "Email", prompt = "tweaked email", builtIn = true),
                        // Not editable: must be skipped without failing the section.
                        item("smart", "Smart", prompt = "x", builtIn = true),
                        // Unknown on this app version: skipped too.
                        item("future_mode", "Future", prompt = "y", builtIn = true),
                    ),
                replaceExisting = false,
            )

        assertEquals(mapOf("email" to "tweaked email"), plan.overrides)
        assertEquals(mapOf("email" to "email"), plan.result.idRemap)
        assertEquals(1, plan.result.updated)
        assertEquals(0, plan.result.inserted)
    }

    @Test
    fun `a blank prompt aborts the whole plan before anything could be committed`() {
        assertThrows(IllegalArgumentException::class.java) {
            planProcessingPresetBackup(
                existingCustomPresets = listOf(custom("user_keep", "Existing")),
                existingOverrides = emptyMap(),
                currentModeId = ProcessingModeDefaults.DEFAULT_MODE_ID,
                items = listOf(item("user_src", "Existing", prompt = "   ")),
                replaceExisting = false,
            )
        }
    }
}
