package dev.chirpboard.app.backup

import androidx.annotation.Keep
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.WordReplacement

/**
 * chirp-backup v1 — the unified Backup & Restore envelope.
 *
 * One JSON document written/read via SAF. Every section is optional; API keys are embedded
 * ONLY as the existing passphrase-encrypted CHIRPKEY container (base64), never plaintext.
 *
 * All payload classes below are Gson reflection targets (REL-02/REL-05): @Keep or R8 strips
 * the fields and the round-trip silently produces empty payloads. Reads treat every field as
 * nullable (Gson bypasses Kotlin null-safety) and validate before use — see [ChirpBackupCodec].
 */
@Keep
data class ChirpBackupEnvelope(
    // format/version intentionally have NO default values: if every parameter defaulted,
    // Kotlin would emit a no-arg constructor, Gson would call it, and a file MISSING these
    // fields would silently inherit valid values — defeating the format/version check.
    val format: String?,
    val version: Int?,
    /** ISO-8601 UTC instant, e.g. 2026-06-12T07:30:00Z. */
    val createdAt: String? = null,
    val appVersion: String? = null,
    val appVersionCode: Long? = null,
    val sections: BackupSectionsPayload? = null,
)

@Keep
data class BackupSectionsPayload(
    val settings: BackupSettingsPayload? = null,
    val tags: List<BackupTagPayload?>? = null,
    val profiles: List<BackupProfilePayload?>? = null,
    val wordReplacements: List<BackupWordReplacementPayload?>? = null,
    val processingPresets: List<BackupProcessingPresetPayload?>? = null,
    /** Base64 of the passphrase-encrypted CHIRPKEY blob (the existing v2 key-backup format). */
    val apiKeys: String? = null,
)

/**
 * App preference snapshot. Deliberately excludes device-bound values: SAF grants (Obsidian
 * vault URIs), the manual input-device address, and one-shot UX flags do not transfer
 * between installs.
 */
@Keep
data class BackupSettingsPayload(
    val useDynamicColor: Boolean? = null,
    val llmEnabled: Boolean? = null,
    val autoTitle: Boolean? = null,
    val autoSummary: Boolean? = null,
    val keyboardSaveRecordings: Boolean? = null,
    val keyboardLlmEnabled: Boolean? = null,
    /** Processing-mode id; empty string means "use the global setting". */
    val keyboardProcessingMode: String? = null,
    val microphoneGain: Float? = null,
    val recordingQuality: String? = null,
    val outputFormat: String? = null,
    val playbackSpeed: Float? = null,
) {
    fun populatedCount(): Int =
        listOf(
            useDynamicColor,
            llmEnabled,
            autoTitle,
            autoSummary,
            keyboardSaveRecordings,
            keyboardLlmEnabled,
            keyboardProcessingMode,
            microphoneGain,
            recordingQuality,
            outputFormat,
            playbackSpeed,
        ).count { it != null }

    companion object {
        /** Number of preferences a full snapshot carries (shown as the section count). */
        const val FIELD_COUNT = 11
    }
}

@Keep
data class BackupTagPayload(
    val id: String? = null,
    val name: String? = null,
    val color: String? = null,
)

@Keep
data class BackupProfilePayload(
    val id: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val defaultProcessingMode: String? = null,
    val autoTranscribe: Boolean? = null,
    val autoTitle: Boolean? = null,
    val autoSummary: Boolean? = null,
    val obsidianVaultPath: String? = null,
    val autoExportToObsidian: Boolean? = null,
    val sortOrder: Int? = null,
    val quickStartPinned: Boolean? = null,
    /** Default tags are referenced by NAME so they survive id changes across devices. */
    val defaultTagNames: List<String?>? = null,
)

@Keep
data class BackupWordReplacementPayload(
    val id: String? = null,
    val original: String? = null,
    val replacement: String? = null,
    val caseSensitive: Boolean? = null,
    val enabled: Boolean? = null,
)

@Keep
data class BackupProcessingPresetPayload(
    /** Mode id for built-ins ("proofread"…); source-device id for custom presets. */
    val id: String? = null,
    val name: String? = null,
    val prompt: String? = null,
    val builtIn: Boolean? = null,
)

// ---------------------------------------------------------------------------------------------
// Validated, non-null domain shapes produced by ChirpBackupCodec.decode (never raw Gson output).
// ---------------------------------------------------------------------------------------------

enum class BackupSection {
    SETTINGS,
    TAGS,
    PROFILES,
    WORD_REPLACEMENTS,
    PROCESSING_PRESETS,
    API_KEYS,
}

enum class BackupImportMode {
    /** Upsert by natural key (tag name, profile name, replacement original, preset name). */
    MERGE,

    /** Clear the section first, then insert the backup's items. */
    REPLACE,
}

/** A profile from the backup together with its by-name default-tag references. */
data class BackupProfileItem(
    val profile: Profile,
    val defaultTagNames: List<String>,
)

/** A processing preset from the backup: a custom preset or a modified built-in prompt. */
data class BackupPresetItem(
    val id: String,
    val name: String,
    val prompt: String,
    val builtIn: Boolean,
)

/** Fully validated backup contents: exactly what an import would apply, nothing more. */
data class ChirpBackupContents(
    val createdAtEpochMs: Long?,
    val appVersion: String?,
    val settings: BackupSettingsPayload?,
    val tags: List<Tag>,
    val profiles: List<BackupProfileItem>,
    val wordReplacements: List<WordReplacement>,
    val processingPresets: List<BackupPresetItem>,
    /** Base64 of the encrypted CHIRPKEY blob, verified decodable at parse time. */
    val apiKeysBase64: String?,
) {
    val availableSections: Set<BackupSection> =
        buildSet {
            if (settings != null && settings.populatedCount() > 0) add(BackupSection.SETTINGS)
            if (tags.isNotEmpty()) add(BackupSection.TAGS)
            if (profiles.isNotEmpty()) add(BackupSection.PROFILES)
            if (wordReplacements.isNotEmpty()) add(BackupSection.WORD_REPLACEMENTS)
            if (processingPresets.isNotEmpty()) add(BackupSection.PROCESSING_PRESETS)
            if (!apiKeysBase64.isNullOrEmpty()) add(BackupSection.API_KEYS)
        }

    fun countFor(section: BackupSection): Int =
        when (section) {
            BackupSection.SETTINGS -> settings?.populatedCount() ?: 0
            BackupSection.TAGS -> tags.size
            BackupSection.PROFILES -> profiles.size
            BackupSection.WORD_REPLACEMENTS -> wordReplacements.size
            BackupSection.PROCESSING_PRESETS -> processingPresets.size
            BackupSection.API_KEYS -> if (!apiKeysBase64.isNullOrEmpty()) 1 else 0
        }
}

/** Why a backup file was rejected before any data was touched. */
class BackupFormatException(
    val reason: Reason,
) : Exception("Backup rejected: $reason") {
    enum class Reason {
        /** Not parseable as JSON at all. */
        UNREADABLE,

        /** Valid JSON but not a chirp-backup envelope. */
        NOT_A_CHIRP_BACKUP,

        /** Envelope from a newer (or nonsensical) format version. */
        UNSUPPORTED_VERSION,

        /** Structurally valid but contains nothing restorable. */
        EMPTY,

        /** Larger than any real chirp-backup; refused before parsing. */
        TOO_LARGE,
    }
}
