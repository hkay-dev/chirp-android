package dev.chirpboard.app.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.data.dao.ProfileDao
import dev.chirpboard.app.data.dao.RecordingEnhancementIntentDao
import dev.chirpboard.app.data.dao.RecordingEnhancementSnapshotDao
import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.StructuredOutcomeSnapshotDao
import dev.chirpboard.app.data.dao.TagDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.dao.WordReplacementDao
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.db.Migrations
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(
                context,
                AppDatabase::class.java,
                AppDatabase.DATABASE_NAME,
            ).addMigrations(*Migrations.ALL)
            // NOTE: No fallbackToDestructiveMigration() - we want migrations to fail loudly
            // so we can write proper migrations instead of silently losing user data
            .build()

    @Provides
    fun provideRecordingDao(database: AppDatabase): RecordingDao = database.recordingDao()

    @Provides
    fun provideTranscriptDao(database: AppDatabase): TranscriptDao = database.transcriptDao()

    @Provides
    fun provideProfileDao(database: AppDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideTagDao(database: AppDatabase): TagDao = database.tagDao()

    @Provides
    fun provideStructuredOutcomeSnapshotDao(database: AppDatabase): StructuredOutcomeSnapshotDao =
        database.structuredOutcomeSnapshotDao()

    @Provides
    fun provideRecordingEnhancementIntentDao(database: AppDatabase): RecordingEnhancementIntentDao =
        database.recordingEnhancementIntentDao()

    @Provides
    fun provideRecordingEnhancementSnapshotDao(database: AppDatabase): RecordingEnhancementSnapshotDao =
        database.recordingEnhancementSnapshotDao()

    @Provides
    fun provideWordReplacementDao(database: AppDatabase): WordReplacementDao = database.wordReplacementDao()
}
