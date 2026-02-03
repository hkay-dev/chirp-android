package dev.parakeeboard.app.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.parakeeboard.app.data.dao.*
import dev.parakeeboard.app.data.db.AppDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }
    
    @Provides
    fun provideRecordingDao(database: AppDatabase): RecordingDao =
        database.recordingDao()
    
    @Provides
    fun provideTranscriptDao(database: AppDatabase): TranscriptDao =
        database.transcriptDao()
    
    @Provides
    fun provideProfileDao(database: AppDatabase): ProfileDao =
        database.profileDao()
    
    @Provides
    fun provideTagDao(database: AppDatabase): TagDao =
        database.tagDao()
    
    @Provides
    fun provideWordReplacementDao(database: AppDatabase): WordReplacementDao =
        database.wordReplacementDao()
}
