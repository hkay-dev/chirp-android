package dev.harsha.parakeeboard.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.harsha.parakeeboard.data.dao.*
import dev.harsha.parakeeboard.data.entity.*

@Database(
    entities = [
        Recording::class,
        Transcript::class,
        Profile::class,
        Tag::class,
        RecordingTag::class,
        WordReplacement::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun profileDao(): ProfileDao
    abstract fun tagDao(): TagDao
    abstract fun wordReplacementDao(): WordReplacementDao
    
    companion object {
        const val DATABASE_NAME = "parakeeboard.db"
    }
}
