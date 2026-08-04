package dev.chirpboard.app.cloud

import androidx.annotation.Keep
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import dev.chirpboard.app.di.CloudDictationCheckpointDataStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Keep
internal data class CloudDictationCheckpoint(
    val jobId: String,
    val uploadSessionUrl: String?,
    val crc32c: String,
    val byteLength: Long,
)

@Singleton
internal class CloudDictationCheckpointStore
    @Inject
    constructor(
        @CloudDictationCheckpointDataStore private val dataStore: DataStore<Preferences>,
    ) {
        private val gson = Gson()

        suspend fun get(recordingId: UUID): CloudDictationCheckpoint? {
            val raw = dataStore.data.first()[key(recordingId)] ?: return null
            return runCatching { gson.fromJson(raw, CloudDictationCheckpoint::class.java) }.getOrNull()
        }

        suspend fun put(
            recordingId: UUID,
            checkpoint: CloudDictationCheckpoint,
        ) {
            dataStore.edit { preferences ->
                preferences[key(recordingId)] = gson.toJson(checkpoint)
            }
        }

        suspend fun clear(recordingId: UUID) {
            dataStore.edit { preferences ->
                preferences.remove(key(recordingId))
            }
        }

        private fun key(recordingId: UUID) = stringPreferencesKey("dictation_$recordingId")
    }
