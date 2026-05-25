package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chirpboard.app.data.entity.StructuredOutcomeSnapshotEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface StructuredOutcomeSnapshotDao {
    @Query("SELECT * FROM structured_outcome_snapshots WHERE recordingId = :recordingId")
    suspend fun getSnapshot(recordingId: UUID): StructuredOutcomeSnapshotEntity?

    @Query("SELECT * FROM structured_outcome_snapshots WHERE recordingId = :recordingId")
    fun getSnapshotFlow(recordingId: UUID): Flow<StructuredOutcomeSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: StructuredOutcomeSnapshotEntity)
}
