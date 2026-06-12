package dev.chirpboard.app.backup

import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.WordReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

class ChirpBackupCodecTest {
    private val tag = Tag(name = "work", color = "#FF5733")
    private val profile =
        Profile(
            name = "Meetings",
            icon = "🎙️",
            defaultProcessingMode = "user_abc",
            autoTranscribe = true,
            autoTitle = true,
            autoSummary = false,
            obsidianVaultPath = "notes/meetings",
            autoExportToObsidian = true,
            sortOrder = 3,
            isQuickStartPinned = true,
        )
    private val replacement =
        WordReplacement(original = "chirpbord", replacement = "Chirp", caseSensitive = true, enabled = false)
    private val preset = BackupPresetItem(id = "user_abc", name = "Standup", prompt = "Summarize this", builtIn = false)
    private val builtInPreset = BackupPresetItem(id = "email", name = "Email", prompt = "Custom email prompt", builtIn = true)
    private val settings =
        BackupSettingsPayload(
            useDynamicColor = true,
            llmEnabled = false,
            autoTitle = true,
            autoSummary = false,
            keyboardSaveRecordings = true,
            keyboardLlmEnabled = false,
            keyboardProcessingMode = "proofread",
            microphoneGain = 2.5f,
            recordingQuality = "balanced",
            outputFormat = "mp3",
            playbackSpeed = 1.5f,
        )
    private val keysBase64 = Base64.getEncoder().encodeToString("CHIRPKEY1-pretend-encrypted".toByteArray())

    private fun fullEnvelope(): ChirpBackupEnvelope =
        ChirpBackupEnvelope(
            format = ChirpBackupCodec.FORMAT,
            version = ChirpBackupCodec.VERSION,
            createdAt = "2026-06-12T08:30:00Z",
            appVersion = "3.1",
            appVersionCode = 31,
            sections =
                BackupSectionsPayload(
                    settings = settings,
                    tags = ChirpBackupCodec.tagsToPayload(listOf(tag)),
                    profiles = listOf(ChirpBackupCodec.profileToPayload(profile, listOf("work", "team"))),
                    wordReplacements = ChirpBackupCodec.wordReplacementsToPayload(listOf(replacement)),
                    processingPresets = ChirpBackupCodec.presetsToPayload(listOf(preset, builtInPreset)),
                    apiKeys = keysBase64,
                ),
        )

    @Test
    fun `every section round trips through encode and decode`() {
        val contents = ChirpBackupCodec.decode(ChirpBackupCodec.encode(fullEnvelope()))

        assertEquals(BackupSection.entries.toSet(), contents.availableSections)
        assertEquals("3.1", contents.appVersion)
        assertNotEquals(null, contents.createdAtEpochMs)

        assertEquals(listOf(tag), contents.tags)
        assertEquals(listOf(replacement), contents.wordReplacements)
        assertEquals(listOf(preset, builtInPreset), contents.processingPresets)
        assertEquals(keysBase64, contents.apiKeysBase64)
        assertEquals(settings, contents.settings)

        val restoredProfile = contents.profiles.single()
        assertEquals(profile, restoredProfile.profile)
        assertEquals(listOf("work", "team"), restoredProfile.defaultTagNames)
    }

    @Test
    fun `section counts match validated contents`() {
        val contents = ChirpBackupCodec.decode(ChirpBackupCodec.encode(fullEnvelope()))

        assertEquals(BackupSettingsPayload.FIELD_COUNT, contents.countFor(BackupSection.SETTINGS))
        assertEquals(1, contents.countFor(BackupSection.TAGS))
        assertEquals(1, contents.countFor(BackupSection.PROFILES))
        assertEquals(1, contents.countFor(BackupSection.WORD_REPLACEMENTS))
        assertEquals(2, contents.countFor(BackupSection.PROCESSING_PRESETS))
        assertEquals(1, contents.countFor(BackupSection.API_KEYS))
    }

    @Test
    fun `garbage input is rejected as unreadable`() {
        assertRejected("{not json at all", BackupFormatException.Reason.UNREADABLE)
    }

    @Test
    fun `valid json that is not a chirp backup is rejected`() {
        assertRejected("""{"hello":"world"}""", BackupFormatException.Reason.NOT_A_CHIRP_BACKUP)
        assertRejected("""{"format":"other-backup","version":1}""", BackupFormatException.Reason.NOT_A_CHIRP_BACKUP)
    }

    @Test
    fun `future and nonsensical versions are rejected`() {
        val future = fullEnvelope().copy(version = ChirpBackupCodec.VERSION + 1)
        assertRejected(ChirpBackupCodec.encode(future), BackupFormatException.Reason.UNSUPPORTED_VERSION)

        val zero = fullEnvelope().copy(version = 0)
        assertRejected(ChirpBackupCodec.encode(zero), BackupFormatException.Reason.UNSUPPORTED_VERSION)

        val missing = fullEnvelope().copy(version = null)
        assertRejected(ChirpBackupCodec.encode(missing), BackupFormatException.Reason.UNSUPPORTED_VERSION)
    }

    @Test
    fun `envelope without restorable data is rejected as empty`() {
        val noSections = ChirpBackupEnvelope(format = ChirpBackupCodec.FORMAT, version = 1, sections = null)
        assertRejected(ChirpBackupCodec.encode(noSections), BackupFormatException.Reason.EMPTY)

        val emptySections =
            ChirpBackupEnvelope(
                format = ChirpBackupCodec.FORMAT,
                version = 1,
                sections = BackupSectionsPayload(),
            )
        assertRejected(ChirpBackupCodec.encode(emptySections), BackupFormatException.Reason.EMPTY)
    }

    @Test
    fun `entries with missing required fields are dropped not fatal`() {
        val json =
            """
            {
              "format": "chirp-backup",
              "version": 1,
              "sections": {
                "tags": [
                  {"id": "not-a-uuid", "name": "valid"},
                  {"name": "   "},
                  {"color": "#FFFFFF"},
                  null
                ],
                "profiles": [
                  {"name": "Valid Profile"},
                  {"icon": "🎤"}
                ],
                "wordReplacements": [
                  {"original": "teh", "replacement": "the"},
                  {"replacement": "orphan"}
                ],
                "processingPresets": [
                  {"id": "user_1", "name": "Good", "prompt": "p"},
                  {"id": "user_2", "name": "No prompt"}
                ]
              }
            }
            """.trimIndent()

        val contents = ChirpBackupCodec.decode(json)

        assertEquals(listOf("valid"), contents.tags.map(Tag::name))
        assertEquals(listOf("Valid Profile"), contents.profiles.map { it.profile.name })
        assertEquals(listOf("teh"), contents.wordReplacements.map(WordReplacement::original))
        assertEquals(listOf("Good"), contents.processingPresets.map(BackupPresetItem::name))
        // Profile defaults survive omission.
        assertTrue(contents.profiles.single().profile.autoTranscribe)
    }

    @Test
    fun `duplicate natural keys keep the first entry`() {
        val sections =
            BackupSectionsPayload(
                tags =
                    listOf(
                        BackupTagPayload(id = UUID.randomUUID().toString(), name = "dup", color = "#111111"),
                        BackupTagPayload(id = UUID.randomUUID().toString(), name = "dup", color = "#222222"),
                    ),
                wordReplacements =
                    listOf(
                        BackupWordReplacementPayload(original = "x", replacement = "first"),
                        BackupWordReplacementPayload(original = "x", replacement = "second"),
                    ),
            )
        val envelope = ChirpBackupEnvelope(format = ChirpBackupCodec.FORMAT, version = 1, sections = sections)

        val contents = ChirpBackupCodec.decode(ChirpBackupCodec.encode(envelope))

        assertEquals(1, contents.tags.size)
        assertEquals("#111111", contents.tags.single().color)
        assertEquals(1, contents.wordReplacements.size)
        assertEquals("first", contents.wordReplacements.single().replacement)
    }

    @Test
    fun `invalid uuid falls back to a generated id`() {
        val sections =
            BackupSectionsPayload(
                tags = listOf(BackupTagPayload(id = "definitely-not-a-uuid", name = "ok")),
            )
        val envelope = ChirpBackupEnvelope(format = ChirpBackupCodec.FORMAT, version = 1, sections = sections)

        val contents = ChirpBackupCodec.decode(ChirpBackupCodec.encode(envelope))

        // No exception, and a usable id was generated.
        assertEquals("ok", contents.tags.single().name)
    }

    @Test
    fun `corrupted api keys blob is treated as absent`() {
        val sections =
            BackupSectionsPayload(
                tags = listOf(BackupTagPayload(id = UUID.randomUUID().toString(), name = "keep")),
                apiKeys = "not&&base64!!",
            )
        val envelope = ChirpBackupEnvelope(format = ChirpBackupCodec.FORMAT, version = 1, sections = sections)

        val contents = ChirpBackupCodec.decode(ChirpBackupCodec.encode(envelope))

        assertNull(contents.apiKeysBase64)
        assertEquals(setOf(BackupSection.TAGS), contents.availableSections)
    }

    @Test
    fun `created timestamp parse failure is tolerated`() {
        val envelope = fullEnvelope().copy(createdAt = "yesterday-ish")
        val contents = ChirpBackupCodec.decode(ChirpBackupCodec.encode(envelope))
        assertNull(contents.createdAtEpochMs)
    }

    @Test
    fun `profiles are ordered by sort order after validation`() {
        val sections =
            BackupSectionsPayload(
                profiles =
                    listOf(
                        BackupProfilePayload(name = "Second", sortOrder = 5),
                        BackupProfilePayload(name = "First", sortOrder = 1),
                    ),
            )
        val envelope = ChirpBackupEnvelope(format = ChirpBackupCodec.FORMAT, version = 1, sections = sections)

        val contents = ChirpBackupCodec.decode(ChirpBackupCodec.encode(envelope))

        assertEquals(listOf("First", "Second"), contents.profiles.map { it.profile.name })
    }

    private fun assertRejected(
        json: String,
        expected: BackupFormatException.Reason,
    ) {
        val error =
            runCatching { ChirpBackupCodec.decode(json) }.exceptionOrNull()
                ?: throw AssertionError("Expected rejection ($expected) but decode succeeded")
        assertTrue("Expected BackupFormatException, got $error", error is BackupFormatException)
        assertEquals(expected, (error as BackupFormatException).reason)
    }
}
