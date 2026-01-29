package dev.parakeeboard.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcriptions")
data class Transcription(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rawText: String,
    val processedText: String?,
    val timestamp: Long = System.currentTimeMillis()
)
