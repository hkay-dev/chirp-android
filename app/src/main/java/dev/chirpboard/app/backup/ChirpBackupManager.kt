package dev.chirpboard.app.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.dao.BackupUpsertCounts
import dev.chirpboard.app.data.dao.ProfileBackupEntry
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.llm.repository.ProcessingPresetBackupItem
import dev.chirpboard.app.feature.llm.settings.LlmApiKeyBackupManager
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import dev.chirpboard.app.feature.llm.settings.SecureStorageUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified Backup & Restore engine (chirp-backup v1).
 *
 * Export: collects the selected sections into one JSON envelope written via SAF. API keys are
 * embedded only as the existing passphrase-encrypted CHIRPKEY container (base64) built by
 * [LlmApiKeyBackupManager] — never plaintext.
 *
 * Import: [inspect] fully parses + validates the file (rejecting malformed input before any
 * write), then [applyImport] applies the chosen sections independently — each Room section in
 * a single transaction — and reports a per-section summary. A failing section never blocks or
 * corrupts the others.
 */
@Singleton
class ChirpBackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val tagRepository: TagRepository,
        private val profileRepository: ProfileRepository,
        private val wordReplacementRepository: WordReplacementRepository,
        private val processingModeRepository: ProcessingModeRepository,
        private val apiKeyBackupManager: LlmApiKeyBackupManager,
        private val llmPreferences: LlmPreferences,
        private val settingsDelegate: SettingsBackupDelegate,
    ) {
        data class SectionCounts(
            val settings: Int,
            val tags: Int,
            val profiles: Int,
            val wordReplacements: Int,
            val processingPresets: Int,
            val apiKeys: Int,
            val isSecureStorageAvailable: Boolean,
        ) {
            fun countFor(section: BackupSection): Int =
                when (section) {
                    BackupSection.SETTINGS -> settings
                    BackupSection.TAGS -> tags
                    BackupSection.PROFILES -> profiles
                    BackupSection.WORD_REPLACEMENTS -> wordReplacements
                    BackupSection.PROCESSING_PRESETS -> processingPresets
                    BackupSection.API_KEYS -> apiKeys
                }
        }

        /** Per-section import outcome for the result summary. */
        data class SectionResult(
            val section: BackupSection,
            val inserted: Int,
            val updated: Int,
            val failure: SectionFailure? = null,
        )

        enum class SectionFailure {
            /** API-keys section: wrong passphrase or corrupted CHIRPKEY payload. */
            KEYS_REJECTED,

            /**
             * API-keys section: the device keystore layer is unusable, so no passphrase can
             * ever succeed. Kept distinct from [KEYS_REJECTED] so the UI doesn't send the user
             * into a retype-the-passphrase loop.
             */
            KEYS_STORAGE_UNAVAILABLE,

            /**
             * Anything else; details are logged. Room sections (each a single @Transaction)
             * and presets (a single DataStore commit) are rolled back/not applied. The
             * SETTINGS section applies each preference as its own commit across several
             * stores, so it can fail partially — the result UI words that section's
             * failure accordingly.
             */
            FAILED,
        }

        data class ImportSummary(
            val results: List<SectionResult>,
        ) {
            val hasFailures: Boolean get() = results.any { it.failure != null }
        }

        fun suggestedBackupFileName(): String {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return "chirp-backup-$date.json"
        }

        suspend fun sectionCounts(): SectionCounts =
            withContext(Dispatchers.IO) {
                SectionCounts(
                    settings = BackupSettingsPayload.FIELD_COUNT,
                    tags = tagRepository.getCount(),
                    profiles = profileRepository.getCount(),
                    wordReplacements = wordReplacementRepository.getCount(),
                    processingPresets = collectPresetItems().size,
                    apiKeys = llmPreferences.countConfiguredApiKeys(),
                    isSecureStorageAvailable = llmPreferences.isSecureStorageAvailable(),
                )
            }

        // region Export

        /**
         * Builds and writes the envelope for [sections]. [passphrase] is required iff
         * [BackupSection.API_KEYS] is selected. Returns the number of sections written.
         */
        suspend fun exportToUri(
            uri: Uri,
            sections: Set<BackupSection>,
            passphrase: CharArray?,
        ): Result<Int> =
            withContext(Dispatchers.IO) {
                runCatching {
                    require(sections.isNotEmpty()) { "No sections selected" }
                    val json = buildBackupJson(sections, passphrase)
                    // "wt" truncates explicitly: the default mode is provider-dependent and can
                    // leave stale trailing bytes when overwriting a longer existing file, which
                    // would corrupt the JSON.
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(json.toByteArray(StandardCharsets.UTF_8))
                    } ?: throw IOException("Could not open backup destination")
                    sections.size
                }
            }

        internal suspend fun buildBackupJson(
            sections: Set<BackupSection>,
            passphrase: CharArray?,
        ): String {
            val apiKeysBase64 =
                if (BackupSection.API_KEYS in sections) {
                    val chars = requireNotNull(passphrase) { "Passphrase required for the API keys section" }
                    val backup = apiKeyBackupManager.buildEncryptedSnapshot(chars).getOrThrow()
                    Base64.getEncoder().encodeToString(backup.bytes)
                } else {
                    null
                }

            val envelope =
                ChirpBackupEnvelope(
                    format = ChirpBackupCodec.FORMAT,
                    version = ChirpBackupCodec.VERSION,
                    createdAt = Instant.now().toString(),
                    appVersion = currentAppVersion(),
                    appVersionCode = currentAppVersionCode(),
                    sections =
                        BackupSectionsPayload(
                            settings =
                                if (BackupSection.SETTINGS in sections) settingsDelegate.snapshot() else null,
                            tags =
                                if (BackupSection.TAGS in sections) {
                                    ChirpBackupCodec.tagsToPayload(tagRepository.getAllTagsList())
                                } else {
                                    null
                                },
                            profiles =
                                if (BackupSection.PROFILES in sections) collectProfilePayloads() else null,
                            wordReplacements =
                                if (BackupSection.WORD_REPLACEMENTS in sections) {
                                    ChirpBackupCodec.wordReplacementsToPayload(
                                        wordReplacementRepository.getAllReplacementsList(),
                                    )
                                } else {
                                    null
                                },
                            processingPresets =
                                if (BackupSection.PROCESSING_PRESETS in sections) {
                                    ChirpBackupCodec.presetsToPayload(collectPresetItems())
                                } else {
                                    null
                                },
                            apiKeys = apiKeysBase64,
                        ),
                )
            return ChirpBackupCodec.encode(envelope)
        }

        private suspend fun collectProfilePayloads(): List<BackupProfilePayload> =
            profileRepository.getAllProfilesList().map { profile ->
                ChirpBackupCodec.profileToPayload(
                    profile = profile,
                    defaultTagNames =
                        profileRepository.getDefaultTagsForProfileList(profile.id).map { it.name },
                )
            }

        /** Custom presets plus built-in presets whose prompt the user modified. */
        private suspend fun collectPresetItems(): List<BackupPresetItem> =
            processingModeRepository.promptPresets.first().mapNotNull { preset ->
                val prompt = preset.prompt
                when {
                    prompt.isNullOrBlank() -> null
                    !preset.isBuiltIn ->
                        BackupPresetItem(id = preset.id, name = preset.name, prompt = prompt, builtIn = false)
                    preset.isModified && preset.canEditPrompt ->
                        BackupPresetItem(id = preset.id, name = preset.name, prompt = prompt, builtIn = true)
                    else -> null
                }
            }

        // endregion

        // region Import

        /** Reads + fully validates the file. Throws [BackupFormatException] on rejection. */
        suspend fun inspect(uri: Uri): Result<ChirpBackupContents> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes =
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            readCapped(input)
                        } ?: throw IOException("Could not read backup file")
                    ChirpBackupCodec.decode(String(bytes, StandardCharsets.UTF_8))
                }
            }

        /**
         * Applies the selected sections. Order matters only for reference repair: tags before
         * profiles (default-tag names), presets before profiles/settings (processing-mode id
         * remap). Every section is independent — one failure never rolls back another.
         *
         * Runs under [NonCancellable]: the caller's scope is a screen-bound viewModelScope,
         * and a back-navigation mid-import must not cancel a section between its destructive
         * and constructive steps (the multi-commit settings delegate) or silently skip the
         * remaining selected sections. The import is short and local, so completing it is
         * always preferable to abandoning it half-applied.
         */
        suspend fun applyImport(
            contents: ChirpBackupContents,
            sections: Set<BackupSection>,
            mode: BackupImportMode,
            passphrase: CharArray?,
        ): ImportSummary =
            withContext(Dispatchers.IO + NonCancellable) {
                val results = mutableListOf<SectionResult>()
                val selected = sections intersect contents.availableSections

                if (BackupSection.TAGS in selected) {
                    results += applySection(BackupSection.TAGS) {
                        when (mode) {
                            BackupImportMode.MERGE -> tagRepository.upsertByNameFromBackup(contents.tags)
                            BackupImportMode.REPLACE -> tagRepository.replaceAllFromBackup(contents.tags)
                        }
                    }
                }

                var presetIdRemap = emptyMap<String, String>()
                if (BackupSection.PROCESSING_PRESETS in selected) {
                    results +=
                        applySection(BackupSection.PROCESSING_PRESETS) {
                            val (counts, remap) = applyPresets(contents.processingPresets, mode)
                            presetIdRemap = remap
                            counts
                        }
                }

                if (BackupSection.PROFILES in selected) {
                    results += applySection(BackupSection.PROFILES) {
                        applyProfiles(contents.profiles, mode, presetIdRemap)
                    }
                }

                if (BackupSection.WORD_REPLACEMENTS in selected) {
                    results += applySection(BackupSection.WORD_REPLACEMENTS) {
                        when (mode) {
                            BackupImportMode.MERGE ->
                                wordReplacementRepository.upsertByOriginalFromBackup(contents.wordReplacements)
                            BackupImportMode.REPLACE ->
                                wordReplacementRepository.replaceAllFromBackup(contents.wordReplacements)
                        }
                    }
                }

                if (BackupSection.SETTINGS in selected) {
                    val settings = contents.settings
                    if (settings != null) {
                        results += applySection(BackupSection.SETTINGS) {
                            BackupUpsertCounts(inserted = 0, updated = settingsDelegate.apply(settings, presetIdRemap))
                        }
                    }
                }

                if (BackupSection.API_KEYS in selected) {
                    results += applyApiKeys(contents.apiKeysBase64, passphrase)
                }

                ImportSummary(results)
            }

        private suspend fun applySection(
            section: BackupSection,
            block: suspend () -> BackupUpsertCounts,
        ): SectionResult =
            try {
                val counts = block()
                SectionResult(section = section, inserted = counts.inserted, updated = counts.updated)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Backup import failed for section $section", error)
                SectionResult(section = section, inserted = 0, updated = 0, failure = SectionFailure.FAILED)
            }

        private suspend fun applyProfiles(
            items: List<BackupProfileItem>,
            mode: BackupImportMode,
            presetIdRemap: Map<String, String>,
        ): BackupUpsertCounts {
            val tagIdsByName = tagRepository.getAllTagsList().associateBy(Tag::name, Tag::id)
            val entries =
                items.map { item ->
                    ProfileBackupEntry(
                        profile =
                            item.profile.copy(
                                defaultProcessingMode =
                                    item.profile.defaultProcessingMode?.let { presetIdRemap[it] ?: it },
                            ),
                        defaultTagIds = item.defaultTagNames.mapNotNull(tagIdsByName::get),
                    )
                }
            return when (mode) {
                BackupImportMode.MERGE -> profileRepository.upsertByNameFromBackup(entries)
                BackupImportMode.REPLACE -> profileRepository.replaceAllFromBackup(entries)
            }
        }

        /**
         * Applies preset items through [ProcessingModeRepository.applyBackupPresets], which
         * commits the whole section in ONE DataStore edit. Looping the public single-preset
         * setters here (the old shape) was not transactional: each call was its own commit,
         * so a failure between the REPLACE deletes and the re-inserts permanently lost the
         * user's custom presets while the result card claimed nothing had changed. Returns
         * the applied counts plus a source-id -> target-id remap used to repair
         * processing-mode references in profiles and keyboard settings.
         *
         * MERGE: built-ins update by mode id (when editable); customs upsert by name.
         * REPLACE: drops all custom presets and resets modified built-ins first.
         */
        private suspend fun applyPresets(
            items: List<BackupPresetItem>,
            mode: BackupImportMode,
        ): Pair<BackupUpsertCounts, Map<String, String>> {
            val result =
                processingModeRepository.applyBackupPresets(
                    items =
                        items.map { item ->
                            ProcessingPresetBackupItem(
                                id = item.id,
                                name = item.name,
                                prompt = item.prompt,
                                builtIn = item.builtIn,
                            )
                        },
                    replaceExisting = mode == BackupImportMode.REPLACE,
                )
            return BackupUpsertCounts(inserted = result.inserted, updated = result.updated) to result.idRemap
        }

        private suspend fun applyApiKeys(
            apiKeysBase64: String?,
            passphrase: CharArray?,
        ): SectionResult {
            if (apiKeysBase64.isNullOrEmpty() || passphrase == null) {
                return SectionResult(
                    section = BackupSection.API_KEYS,
                    inserted = 0,
                    updated = 0,
                    failure = SectionFailure.KEYS_REJECTED,
                )
            }
            val blob =
                runCatching { Base64.getDecoder().decode(apiKeysBase64) }.getOrNull()
                    ?: return SectionResult(
                        section = BackupSection.API_KEYS,
                        inserted = 0,
                        updated = 0,
                        failure = SectionFailure.KEYS_REJECTED,
                    )
            return apiKeyBackupManager.restoreEncryptedSnapshot(blob, passphrase).fold(
                onSuccess = { keyCount ->
                    SectionResult(section = BackupSection.API_KEYS, inserted = keyCount, updated = 0)
                },
                onFailure = { error ->
                    Log.w(TAG, "API key restore rejected", error)
                    SectionResult(
                        section = BackupSection.API_KEYS,
                        inserted = 0,
                        updated = 0,
                        failure =
                            if (error is SecureStorageUnavailableException) {
                                SectionFailure.KEYS_STORAGE_UNAVAILABLE
                            } else {
                                SectionFailure.KEYS_REJECTED
                            },
                    )
                },
            )
        }

        // endregion

        private fun currentAppVersion(): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"

        private fun currentAppVersionCode(): Long =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            }.getOrNull() ?: 0L

        private fun readCapped(input: java.io.InputStream): ByteArray {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                total += read
                if (total > MAX_BACKUP_FILE_BYTES) {
                    throw BackupFormatException(BackupFormatException.Reason.TOO_LARGE)
                }
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }

        companion object {
            private const val TAG = "ChirpBackupManager"

            /** Defensive cap: a real chirp-backup is tens of KB; refuse multi-hundred-MB files. */
            private const val MAX_BACKUP_FILE_BYTES = 16 * 1024 * 1024
        }
    }
