package dev.chirpboard.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.chirpboard.app.data.dao.ProfileDao
import dev.chirpboard.app.data.dao.RecordingEnhancementIntentDao
import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.StructuredOutcomeSnapshotDao
import dev.chirpboard.app.data.dao.TagDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.dao.WordReplacementDao
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.RecordingEnhancementIntentEntity
import dev.chirpboard.app.data.entity.RecordingTag
import dev.chirpboard.app.data.entity.StructuredOutcomeSnapshotEntity
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.entity.WordReplacement

/**
 * AppDatabase - Main application database
 *
 * Schema Version History:
 * - Version 1: Initial schema
 * - Version 2: Added composite indices to fix N+1 queries
 * - Version 3: Added persisted home quick-start pin membership to profiles
 *   - Version 4: Added dedicated transcript timing rows keyed by recording and sequence order
 *   - Version 5: Added additive manual transcript correction fields
 *   - Version 6: Added structured outcome snapshot persistence for recording review
 *   - Version 7: Added persisted recording enhancement intents
 *
 * Current Schema (v7):
 *
 * recordings:
 *   - id: TEXT (PK, UUID)
 *   - title: TEXT
 *   - audioPath: TEXT
 *   - status: TEXT (RecordingStatus enum)
 *   - source: TEXT (RecordingSource enum)
 *   - profileId: TEXT (FK -> profiles.id, nullable, SET_NULL on delete)
 *   - createdAt: INTEGER (Date as timestamp)
 *   - durationMs: INTEGER
 *   - errorMessage: TEXT (nullable)
 *   - lastExportedPath: TEXT (nullable)
 *   - lastExportedAt: INTEGER (nullable, Date as timestamp)
 *   Indices: profileId, createdAt, status
 *
 * transcripts:
 *   - id: TEXT (PK, UUID)
 *   - recordingId: TEXT (FK -> recordings.id, CASCADE on delete)
 *   - rawText: TEXT
 *   - processedText: TEXT (nullable)
 *   - processingMode: TEXT (nullable)
 *   - manualCorrectionText: TEXT (nullable)
 *   - manualCorrectionSourceText: TEXT (nullable)
 *   - summary: TEXT (nullable)
 *   - createdAt: INTEGER (Date as timestamp)
 *   - updatedAt: INTEGER (Date as timestamp)
 *   Indices: recordingId (unique)
 *
 * transcript_timings:
 *   - recordingId: TEXT (PK/FK -> recordings.id, CASCADE on delete)
 *   - sequenceIndex: INTEGER (PK, stable display order)
 *   - text: TEXT
 *   - startOffsetMs: INTEGER
 *   - endOffsetMs: INTEGER
 *   Indices: recordingId
 *
 * structured_outcome_snapshots:
 *   - recordingId: TEXT (PK/FK -> recordings.id, CASCADE on delete)
 *   - sourceTranscriptRevision: TEXT (nullable)
 *   - generationStatus: TEXT
 *   - generatedAt: INTEGER (nullable, Date as timestamp)
 *   - lastAttemptedAt: INTEGER (Date as timestamp)
 *   - failureMessage: TEXT (nullable)
 *   - taskItemsPayload: TEXT (nullable, base64 line list)
 *   - decisionItemsPayload: TEXT (nullable, base64 line list)
 *   - followUpItemsPayload: TEXT (nullable, base64 line list)
 *
 * recording_enhancement_intents:
 *   - recordingId: TEXT (PK/FK -> recordings.id, CASCADE on delete)
 *   - processingModeId: TEXT (nullable)
 *   - autoTitle: INTEGER (boolean)
 *   - autoSummary: INTEGER (boolean)
 *   - createdAt: INTEGER (Date as timestamp)
 *   - lastAttemptedAt: INTEGER (nullable, Date as timestamp)
 *   - lastErrorMessage: TEXT (nullable)
 *
 * profiles:
 *   - id: TEXT (PK, UUID)
 *   - name: TEXT
 *   - icon: TEXT (nullable, emoji)
 *   - defaultProcessingMode: TEXT (nullable)
 *   - autoTranscribe: INTEGER (boolean)
 *   - autoTitle: INTEGER (boolean)
 *   - autoSummary: INTEGER (boolean)
 *   - obsidianVaultPath: TEXT (nullable)
 *   - autoExportToObsidian: INTEGER (boolean)
 *   - defaultTagIds: TEXT (nullable, comma-separated UUIDs)
 *   - sortOrder: INTEGER
 *   - isQuickStartPinned: INTEGER (boolean)
 *
 * tags:
 *   - id: TEXT (PK, UUID)
 *   - name: TEXT
 *   - color: TEXT (nullable, hex color)
 *
 * recording_tags:
 *   - recordingId: TEXT (PK, FK -> recordings.id, CASCADE on delete)
 *   - tagId: TEXT (PK, FK -> tags.id, CASCADE on delete)
 *   Indices: tagId
 *
 * word_replacements:
 *   - id: TEXT (PK, UUID)
 *   - original: TEXT
 *   - replacement: TEXT
 *   - caseSensitive: INTEGER (boolean)
 *   - enabled: INTEGER (boolean)
 *   Indices: original
 */
@Database(
    entities = [
        Recording::class,
        Transcript::class,
        TranscriptTiming::class,
        StructuredOutcomeSnapshotEntity::class,
        RecordingEnhancementIntentEntity::class,
        Profile::class,
        Tag::class,
        RecordingTag::class,
        WordReplacement::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    abstract fun transcriptDao(): TranscriptDao

    abstract fun structuredOutcomeSnapshotDao(): StructuredOutcomeSnapshotDao

    abstract fun recordingEnhancementIntentDao(): RecordingEnhancementIntentDao

    abstract fun profileDao(): ProfileDao

    abstract fun tagDao(): TagDao

    abstract fun wordReplacementDao(): WordReplacementDao

    companion object {
        const val DATABASE_NAME = "chirp.db"
    }
}
