package dev.parakeeboard.app.data.db

import androidx.room.TypeConverter
import dev.parakeeboard.app.data.model.RecordingSource
import dev.parakeeboard.app.data.model.RecordingStatus
import java.util.Date
import java.util.UUID

class Converters {
    
    // UUID converters
    @TypeConverter
    fun fromUUID(uuid: UUID?): String? = uuid?.toString()
    
    @TypeConverter
    fun toUUID(value: String?): UUID? = value?.let { UUID.fromString(it) }
    
    // Date converters
    @TypeConverter
    fun fromDate(date: Date?): Long? = date?.time
    
    @TypeConverter
    fun toDate(value: Long?): Date? = value?.let { Date(it) }
    
    // RecordingSource converters
    @TypeConverter
    fun fromRecordingSource(source: RecordingSource?): String? = source?.name
    
    @TypeConverter
    fun toRecordingSource(value: String?): RecordingSource? = value?.let { RecordingSource.valueOf(it) }
    
    // RecordingStatus converters
    @TypeConverter
    fun fromRecordingStatus(status: RecordingStatus?): String? = status?.name
    
    @TypeConverter
    fun toRecordingStatus(value: String?): RecordingStatus? = value?.let { RecordingStatus.valueOf(it) }
}
