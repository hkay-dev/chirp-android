package dev.chirpboard.app.feature.llm.repository

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * DAT-013: codec for the two structured JSON blobs stored inside the
 * `processing_mode_preferences` DataStore (prompt overrides + custom presets).
 *
 * Two historic hazards are closed here:
 *  1. The blobs carried no schema version, so any future shape change would silently
 *     half-parse old data. Writes now wrap the payload in a versioned envelope
 *     (`{"v":1,"overrides":{...}}` / `{"v":1,"presets":[...]}`); reads accept both the
 *     envelope and the legacy unversioned shape (a bare map / bare list), upgrading it on
 *     the next write.
 *  2. Gson instantiates Kotlin data classes via unsafe allocation, so a missing field
 *     produced a `null` inside a non-null `String` property that only exploded later in the
 *     UI. Reads therefore parse into fully nullable mirror types and validate every field
 *     before constructing the non-null domain shape, dropping invalid entries.
 *
 * Unknown FUTURE versions are read best-effort: recognizable fields are kept (after the same
 * null validation) rather than wiping the user's prompts.
 */
internal object ProcessingModeStoreCodec {
    /** Current schema version written by this build. */
    internal const val SCHEMA_VERSION = 1

    private val gson = Gson()

    // region Prompt overrides (modeId -> prompt text)

    fun encodeOverrides(overrides: Map<String, String>): String =
        gson.toJson(OverridesEnvelope(v = SCHEMA_VERSION, overrides = overrides))

    fun decodeOverrides(json: String): Map<String, String> {
        val envelope =
            runCatching { gson.fromJson(json, OverridesEnvelope::class.java) }.getOrNull()
        val raw: Map<String, String?>? =
            if (envelope?.v != null) {
                envelope.overrides
            } else {
                // Legacy unversioned shape: a bare {"modeId": "prompt"} object. (A legacy map
                // cannot collide with the envelope: override keys are mode ids, never "v".)
                runCatching {
                    val type = object : TypeToken<Map<String, String?>>() {}.type
                    gson.fromJson<Map<String, String?>>(json, type)
                }.getOrNull()
            }
        if (raw == null) return emptyMap()
        return buildMap {
            for ((key, value) in raw) {
                if (key.isNotEmpty() && value != null) put(key, value)
            }
        }
    }

    // endregion

    // region Custom presets

    fun encodePresets(presets: List<StoredCustomPreset>): String =
        gson.toJson(
            PresetsEnvelope(
                v = SCHEMA_VERSION,
                presets =
                    presets.map {
                        RawStoredPreset(
                            id = it.id,
                            name = it.name,
                            prompt = it.prompt,
                            originalPrompt = it.originalPrompt,
                        )
                    },
            ),
        )

    fun decodePresets(json: String): List<StoredCustomPreset> {
        val envelope =
            runCatching { gson.fromJson(json, PresetsEnvelope::class.java) }.getOrNull()
        val raw: List<RawStoredPreset?>? =
            if (envelope?.v != null) {
                envelope.presets
            } else {
                // Legacy unversioned shape: a bare [...] array of presets.
                runCatching {
                    val type = object : TypeToken<List<RawStoredPreset?>>() {}.type
                    gson.fromJson<List<RawStoredPreset?>>(json, type)
                }.getOrNull()
            }
        if (raw == null) return emptyList()
        return raw.mapNotNull { it?.toValidatedPresetOrNull() }
    }

    private fun RawStoredPreset.toValidatedPresetOrNull(): StoredCustomPreset? {
        val validId = id?.takeIf { it.isNotBlank() } ?: return null
        val validName = name?.takeIf { it.isNotBlank() } ?: return null
        val validPrompt = prompt?.takeIf { it.isNotBlank() } ?: return null
        return StoredCustomPreset(
            id = validId,
            name = validName,
            prompt = validPrompt,
            // A missing original is recoverable: treat the current prompt as the original.
            originalPrompt = originalPrompt?.takeIf { it.isNotBlank() } ?: validPrompt,
        )
    }

    // endregion

    // REL-02/REL-05: the three mirrors below are Gson reflection targets — @Keep or R8 strips
    // their fields and decode silently returns empty (user prompts/presets would "vanish").
    // The TypeToken generic signatures are protected by the Gson section of proguard-rules.pro.

    /**
     * Nullable mirror of the persisted envelope: Gson can null any field. (JSON object keys
     * can never be null, so only the values need the nullable treatment.)
     */
    @Keep
    private data class OverridesEnvelope(
        val v: Int?,
        val overrides: Map<String, String?>?,
    )

    @Keep
    private data class PresetsEnvelope(
        val v: Int?,
        val presets: List<RawStoredPreset?>?,
    )

    /** Fully nullable mirror of [StoredCustomPreset]; validated before use. */
    @Keep
    private data class RawStoredPreset(
        val id: String?,
        val name: String?,
        val prompt: String?,
        val originalPrompt: String?,
    )
}
