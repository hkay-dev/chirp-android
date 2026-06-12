package dev.chirpboard.app.backup

import android.content.Context
import android.util.Log
import dev.chirpboard.app.data.dao.BackupUpsertCounts
import dev.chirpboard.app.data.dao.ProfileBackupEntry
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.llm.settings.LlmApiKeyBackupManager
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Orchestration semantics of [ChirpBackupManager.applyImport]: mode dispatch, reference
 * repair across sections (tags by name, preset ids), and per-section failure isolation.
 */
class ChirpBackupManagerTest {
    private val context = mockk<Context>(relaxed = true)
    private val tagRepository = mockk<TagRepository>()
    private val profileRepository = mockk<ProfileRepository>()
    private val wordReplacementRepository = mockk<WordReplacementRepository>()
    private val processingModeRepository = mockk<ProcessingModeRepository>()
    private val apiKeyBackupManager = mockk<LlmApiKeyBackupManager>()
    private val llmPreferences = mockk<LlmPreferences>()
    private val settingsDelegate = mockk<SettingsBackupDelegate>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun manager(): ChirpBackupManager =
        ChirpBackupManager(
            context = context,
            tagRepository = tagRepository,
            profileRepository = profileRepository,
            wordReplacementRepository = wordReplacementRepository,
            processingModeRepository = processingModeRepository,
            apiKeyBackupManager = apiKeyBackupManager,
            llmPreferences = llmPreferences,
            settingsDelegate = settingsDelegate,
        )

    private fun contents(
        settings: BackupSettingsPayload? = null,
        tags: List<Tag> = emptyList(),
        profiles: List<BackupProfileItem> = emptyList(),
        wordReplacements: List<WordReplacement> = emptyList(),
        processingPresets: List<BackupPresetItem> = emptyList(),
        apiKeysBase64: String? = null,
    ): ChirpBackupContents =
        ChirpBackupContents(
            createdAtEpochMs = null,
            appVersion = "3.1",
            settings = settings,
            tags = tags,
            profiles = profiles,
            wordReplacements = wordReplacements,
            processingPresets = processingPresets,
            apiKeysBase64 = apiKeysBase64,
        )

    @Test
    fun `merge mode dispatches the upsert repository paths`() =
        runTest {
            val tags = listOf(Tag(name = "t"))
            val replacements = listOf(WordReplacement(original = "a", replacement = "b"))
            coEvery { tagRepository.upsertByNameFromBackup(tags) } returns BackupUpsertCounts(1, 0)
            coEvery { tagRepository.getAllTagsList() } returns tags
            coEvery { wordReplacementRepository.upsertByOriginalFromBackup(replacements) } returns BackupUpsertCounts(1, 0)

            val summary =
                manager().applyImport(
                    contents = contents(tags = tags, wordReplacements = replacements),
                    sections = setOf(BackupSection.TAGS, BackupSection.WORD_REPLACEMENTS),
                    mode = BackupImportMode.MERGE,
                    passphrase = null,
                )

            assertEquals(2, summary.results.size)
            assertTrue(summary.results.all { it.failure == null })
            coVerify(exactly = 1) { tagRepository.upsertByNameFromBackup(tags) }
            coVerify(exactly = 1) { wordReplacementRepository.upsertByOriginalFromBackup(replacements) }
        }

    @Test
    fun `replace mode dispatches the replace repository paths`() =
        runTest {
            val tags = listOf(Tag(name = "t"))
            coEvery { tagRepository.replaceAllFromBackup(tags) } returns BackupUpsertCounts(1, 0)

            val summary =
                manager().applyImport(
                    contents = contents(tags = tags),
                    sections = setOf(BackupSection.TAGS),
                    mode = BackupImportMode.REPLACE,
                    passphrase = null,
                )

            assertEquals(0, summary.results.single().updated)
            coVerify(exactly = 1) { tagRepository.replaceAllFromBackup(tags) }
        }

    @Test
    fun `profiles get default tags resolved by name and preset ids remapped`() =
        runTest {
            val existingTag = Tag(name = "work")
            coEvery { tagRepository.getAllTagsList() } returns listOf(existingTag)

            // Custom preset "Standup" exists on the source device as user_old; this device
            // has no custom presets, so it is inserted and receives user_new.
            every { processingModeRepository.promptPresets } returns flowOf(emptyList())
            coEvery { processingModeRepository.addCustomPreset("Standup", "prompt") } returns "user_new"

            val profile = Profile(name = "Meetings", defaultProcessingMode = "user_old")
            val entriesSlot = slot<List<ProfileBackupEntry>>()
            coEvery { profileRepository.upsertByNameFromBackup(capture(entriesSlot)) } returns BackupUpsertCounts(1, 0)

            val summary =
                manager().applyImport(
                    contents =
                        contents(
                            profiles = listOf(BackupProfileItem(profile, listOf("work", "missing-tag"))),
                            processingPresets =
                                listOf(BackupPresetItem(id = "user_old", name = "Standup", prompt = "prompt", builtIn = false)),
                        ),
                    sections = setOf(BackupSection.PROFILES, BackupSection.PROCESSING_PRESETS),
                    mode = BackupImportMode.MERGE,
                    passphrase = null,
                )

            assertTrue(summary.results.all { it.failure == null })
            val entry = entriesSlot.captured.single()
            assertEquals("user_new", entry.profile.defaultProcessingMode)
            assertEquals(listOf(existingTag.id), entry.defaultTagIds)
        }

    @Test
    fun `preset remap also reaches the settings section`() =
        runTest {
            every { processingModeRepository.promptPresets } returns flowOf(emptyList())
            coEvery { processingModeRepository.addCustomPreset("Standup", "prompt") } returns "user_new"

            val settings = BackupSettingsPayload(keyboardProcessingMode = "user_old")
            val remapSlot = slot<Map<String, String>>()
            coEvery { settingsDelegate.apply(settings, capture(remapSlot)) } returns 1

            manager().applyImport(
                contents =
                    contents(
                        settings = settings,
                        processingPresets =
                            listOf(BackupPresetItem(id = "user_old", name = "Standup", prompt = "prompt", builtIn = false)),
                    ),
                sections = setOf(BackupSection.SETTINGS, BackupSection.PROCESSING_PRESETS),
                mode = BackupImportMode.MERGE,
                passphrase = null,
            )

            assertEquals("user_new", remapSlot.captured["user_old"])
        }

    @Test
    fun `merge updates an existing custom preset matched by name`() =
        runTest {
            val existing =
                ProcessingPromptPreset(
                    id = "user_local",
                    name = "Standup",
                    prompt = "old prompt",
                    originalPrompt = "old prompt",
                    isBuiltIn = false,
                    isModified = false,
                    canEditPrompt = true,
                )
            every { processingModeRepository.promptPresets } returns flowOf(listOf(existing))
            coEvery { processingModeRepository.updatePresetPrompt("user_local", "new prompt") } returns Unit

            val summary =
                manager().applyImport(
                    contents =
                        contents(
                            processingPresets =
                                listOf(BackupPresetItem(id = "user_src", name = "Standup", prompt = "new prompt", builtIn = false)),
                        ),
                    sections = setOf(BackupSection.PROCESSING_PRESETS),
                    mode = BackupImportMode.MERGE,
                    passphrase = null,
                )

            assertEquals(1, summary.results.single().updated)
            coVerify(exactly = 1) { processingModeRepository.updatePresetPrompt("user_local", "new prompt") }
        }

    @Test
    fun `replace clears custom presets and modified built-ins before applying`() =
        runTest {
            val custom =
                ProcessingPromptPreset(
                    id = "user_gone",
                    name = "Old Custom",
                    prompt = "x",
                    originalPrompt = "x",
                    isBuiltIn = false,
                    isModified = false,
                    canEditPrompt = true,
                )
            val modifiedBuiltIn =
                ProcessingPromptPreset(
                    id = "email",
                    name = "Email",
                    prompt = "tweaked",
                    originalPrompt = "default",
                    isBuiltIn = true,
                    isModified = true,
                    canEditPrompt = true,
                )
            every { processingModeRepository.promptPresets } returns flowOf(listOf(custom, modifiedBuiltIn))
            coEvery { processingModeRepository.deleteCustomPreset("user_gone") } returns Unit
            coEvery { processingModeRepository.resetPresetPrompt("email") } returns Unit
            coEvery { processingModeRepository.addCustomPreset("Imported", "p") } returns "user_new"

            manager().applyImport(
                contents =
                    contents(
                        processingPresets =
                            listOf(BackupPresetItem(id = "user_i", name = "Imported", prompt = "p", builtIn = false)),
                    ),
                sections = setOf(BackupSection.PROCESSING_PRESETS),
                mode = BackupImportMode.REPLACE,
                passphrase = null,
            )

            coVerify(exactly = 1) { processingModeRepository.deleteCustomPreset("user_gone") }
            coVerify(exactly = 1) { processingModeRepository.resetPresetPrompt("email") }
            coVerify(exactly = 1) { processingModeRepository.addCustomPreset("Imported", "p") }
        }

    @Test
    fun `a failing section does not block the others`() =
        runTest {
            val tags = listOf(Tag(name = "t"))
            val replacements = listOf(WordReplacement(original = "a", replacement = "b"))
            coEvery { tagRepository.upsertByNameFromBackup(any()) } throws IllegalStateException("disk full")
            coEvery { tagRepository.getAllTagsList() } returns emptyList()
            coEvery { wordReplacementRepository.upsertByOriginalFromBackup(replacements) } returns BackupUpsertCounts(1, 0)

            val summary =
                manager().applyImport(
                    contents = contents(tags = tags, wordReplacements = replacements),
                    sections = setOf(BackupSection.TAGS, BackupSection.WORD_REPLACEMENTS),
                    mode = BackupImportMode.MERGE,
                    passphrase = null,
                )

            val tagResult = summary.results.single { it.section == BackupSection.TAGS }
            val replacementResult = summary.results.single { it.section == BackupSection.WORD_REPLACEMENTS }
            assertEquals(ChirpBackupManager.SectionFailure.FAILED, tagResult.failure)
            assertNull(replacementResult.failure)
            assertEquals(1, replacementResult.inserted)
            assertTrue(summary.hasFailures)
        }

    @Test
    fun `wrong passphrase marks only the keys section as rejected`() =
        runTest {
            val blob = Base64.getEncoder().encodeToString("encrypted".toByteArray())
            coEvery { apiKeyBackupManager.restoreEncryptedSnapshot(any(), any()) } returns
                Result.failure(IllegalArgumentException("Incorrect passphrase or corrupted backup file"))
            coEvery { tagRepository.upsertByNameFromBackup(any()) } returns BackupUpsertCounts(1, 0)
            coEvery { tagRepository.getAllTagsList() } returns emptyList()

            val summary =
                manager().applyImport(
                    contents = contents(tags = listOf(Tag(name = "t")), apiKeysBase64 = blob),
                    sections = setOf(BackupSection.TAGS, BackupSection.API_KEYS),
                    mode = BackupImportMode.MERGE,
                    passphrase = "wrong-passphrase".toCharArray(),
                )

            val keysResult = summary.results.single { it.section == BackupSection.API_KEYS }
            assertEquals(ChirpBackupManager.SectionFailure.KEYS_REJECTED, keysResult.failure)
            assertNull(summary.results.single { it.section == BackupSection.TAGS }.failure)
        }

    @Test
    fun `missing passphrase rejects the keys section without touching the manager`() =
        runTest {
            val blob = Base64.getEncoder().encodeToString("encrypted".toByteArray())

            val summary =
                manager().applyImport(
                    contents = contents(apiKeysBase64 = blob),
                    sections = setOf(BackupSection.API_KEYS),
                    mode = BackupImportMode.MERGE,
                    passphrase = null,
                )

            assertEquals(
                ChirpBackupManager.SectionFailure.KEYS_REJECTED,
                summary.results.single().failure,
            )
            coVerify(exactly = 0) { apiKeyBackupManager.restoreEncryptedSnapshot(any(), any()) }
        }

    @Test
    fun `sections not present in the backup are skipped even when selected`() =
        runTest {
            coEvery { tagRepository.upsertByNameFromBackup(any()) } returns BackupUpsertCounts(1, 0)
            coEvery { tagRepository.getAllTagsList() } returns emptyList()

            val summary =
                manager().applyImport(
                    contents = contents(tags = listOf(Tag(name = "only-tags"))),
                    sections = setOf(BackupSection.TAGS, BackupSection.PROFILES, BackupSection.API_KEYS),
                    mode = BackupImportMode.MERGE,
                    passphrase = null,
                )

            assertEquals(listOf(BackupSection.TAGS), summary.results.map { it.section })
        }
}
