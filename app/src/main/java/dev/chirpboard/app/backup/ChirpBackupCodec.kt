package dev.chirpboard.app.backup

import com.google.gson.GsonBuilder
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.WordReplacement
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * Encoder/decoder for the chirp-backup v1 JSON envelope.
 *
 * Decode philosophy (the safety contract): the file is FULLY validated before anything is
 * applied. Structural problems reject the whole file with a typed [BackupFormatException];
 * individually broken entries (blank names, null fields Gson smuggled into non-null shapes)
 * are dropped — so the per-section counts shown in the import preview are exactly what an
 * import would write. Malformed input can therefore never half-apply.
 */
object ChirpBackupCodec {
    const val FORMAT = "chirp-backup"
    const val VERSION = 1

    /** Pretty-printed so the backup file stays human-inspectable. */
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun encode(envelope: ChirpBackupEnvelope): String = gson.toJson(envelope)

    @Suppress("ThrowsCount")
    fun decode(json: String): ChirpBackupContents {
        val envelope =
            runCatching { gson.fromJson(json, ChirpBackupEnvelope::class.java) }
                .getOrElse { throw BackupFormatException(BackupFormatException.Reason.UNREADABLE) }
                ?: throw BackupFormatException(BackupFormatException.Reason.UNREADABLE)

        if (envelope.format != FORMAT) {
            throw BackupFormatException(BackupFormatException.Reason.NOT_A_CHIRP_BACKUP)
        }
        val version = envelope.version
        if (version == null || version < 1 || version > VERSION) {
            throw BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_VERSION)
        }
        val sections = envelope.sections
            ?: throw BackupFormatException(BackupFormatException.Reason.EMPTY)

        val contents =
            ChirpBackupContents(
                createdAtEpochMs = parseCreatedAt(envelope.createdAt),
                appVersion = envelope.appVersion?.takeIf { it.isNotBlank() },
                settings = sections.settings?.takeIf { it.populatedCount() > 0 },
                tags = validateTags(sections.tags),
                profiles = validateProfiles(sections.profiles),
                wordReplacements = validateWordReplacements(sections.wordReplacements),
                processingPresets = validatePresets(sections.processingPresets),
                apiKeysBase64 = validateApiKeysBlob(sections.apiKeys),
            )

        if (contents.availableSections.isEmpty()) {
            throw BackupFormatException(BackupFormatException.Reason.EMPTY)
        }
        return contents
    }

    // region Section payload builders (export side)

    fun tagsToPayload(tags: List<Tag>): List<BackupTagPayload> =
        tags.map { tag ->
            BackupTagPayload(id = tag.id.toString(), name = tag.name, color = tag.color)
        }

    fun profileToPayload(
        profile: Profile,
        defaultTagNames: List<String>,
    ): BackupProfilePayload =
        BackupProfilePayload(
            id = profile.id.toString(),
            name = profile.name,
            icon = profile.icon,
            defaultProcessingMode = profile.defaultProcessingMode,
            autoTranscribe = profile.autoTranscribe,
            autoTitle = profile.autoTitle,
            autoSummary = profile.autoSummary,
            obsidianVaultPath = profile.obsidianVaultPath,
            autoExportToObsidian = profile.autoExportToObsidian,
            sortOrder = profile.sortOrder,
            quickStartPinned = profile.isQuickStartPinned,
            defaultTagNames = defaultTagNames,
        )

    fun wordReplacementsToPayload(replacements: List<WordReplacement>): List<BackupWordReplacementPayload> =
        replacements.map { replacement ->
            BackupWordReplacementPayload(
                id = replacement.id.toString(),
                original = replacement.original,
                replacement = replacement.replacement,
                caseSensitive = replacement.caseSensitive,
                enabled = replacement.enabled,
            )
        }

    fun presetsToPayload(presets: List<BackupPresetItem>): List<BackupProcessingPresetPayload> =
        presets.map { preset ->
            BackupProcessingPresetPayload(
                id = preset.id,
                name = preset.name,
                prompt = preset.prompt,
                builtIn = preset.builtIn,
            )
        }

    // endregion

    // region Validation (import side)

    private fun parseCreatedAt(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun validateTags(payload: List<BackupTagPayload?>?): List<Tag> {
        if (payload == null) return emptyList()
        val seenNames = mutableSetOf<String>()
        return payload.mapNotNull { raw ->
            val name = raw?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!seenNames.add(name)) return@mapNotNull null
            Tag(
                id = parseUuidOrRandom(raw.id),
                name = name,
                color = raw.color?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun validateProfiles(payload: List<BackupProfilePayload?>?): List<BackupProfileItem> {
        if (payload == null) return emptyList()
        val seenNames = mutableSetOf<String>()
        return payload
            .mapNotNull { raw ->
                val name = raw?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (!seenNames.add(name)) return@mapNotNull null
                BackupProfileItem(
                    profile =
                        Profile(
                            id = parseUuidOrRandom(raw.id),
                            name = name,
                            icon = raw.icon?.takeIf { it.isNotBlank() },
                            defaultProcessingMode = raw.defaultProcessingMode?.takeIf { it.isNotBlank() },
                            autoTranscribe = raw.autoTranscribe ?: true,
                            autoTitle = raw.autoTitle ?: false,
                            autoSummary = raw.autoSummary ?: false,
                            obsidianVaultPath = raw.obsidianVaultPath?.takeIf { it.isNotBlank() },
                            autoExportToObsidian = raw.autoExportToObsidian ?: false,
                            sortOrder = raw.sortOrder ?: 0,
                            isQuickStartPinned = raw.quickStartPinned ?: false,
                        ),
                    defaultTagNames =
                        raw.defaultTagNames.orEmpty()
                            .mapNotNull { tagName -> tagName?.trim()?.takeIf { it.isNotEmpty() } }
                            .distinct(),
                )
            }
            .sortedBy { it.profile.sortOrder }
    }

    private fun validateWordReplacements(payload: List<BackupWordReplacementPayload?>?): List<WordReplacement> {
        if (payload == null) return emptyList()
        val seenOriginals = mutableSetOf<String>()
        return payload.mapNotNull { raw ->
            val original = raw?.original?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!seenOriginals.add(original)) return@mapNotNull null
            WordReplacement(
                id = parseUuidOrRandom(raw.id),
                original = original,
                replacement = raw.replacement.orEmpty(),
                caseSensitive = raw.caseSensitive ?: false,
                enabled = raw.enabled ?: true,
            )
        }
    }

    private fun validatePresets(payload: List<BackupProcessingPresetPayload?>?): List<BackupPresetItem> {
        if (payload == null) return emptyList()
        val seenKeys = mutableSetOf<String>()
        return payload.mapNotNull { raw ->
            val id = raw?.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = raw.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val prompt = raw.prompt?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val builtIn = raw.builtIn ?: false
            // Built-ins are keyed by mode id, customs by name (the merge natural key).
            val key = if (builtIn) "builtin:$id" else "custom:$name"
            if (!seenKeys.add(key)) return@mapNotNull null
            BackupPresetItem(id = id, name = name, prompt = prompt, builtIn = builtIn)
        }
    }

    /**
     * Returns the base64 string only when it decodes to a non-empty blob; a corrupted keys
     * section is treated as absent rather than poisoning the whole file.
     */
    private fun validateApiKeysBlob(base64: String?): String? {
        if (base64.isNullOrBlank()) return null
        val decoded =
            runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull()
                ?: return null
        return if (decoded.isEmpty()) null else base64.trim()
    }

    private fun parseUuidOrRandom(raw: String?): UUID =
        raw?.let { candidate -> runCatching { UUID.fromString(candidate.trim()) }.getOrNull() }
            ?: UUID.randomUUID()

    // endregion
}
