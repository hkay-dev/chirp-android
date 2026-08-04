package dev.chirpboard.app.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.di.TranscriptionRoutingDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class TranscriptionRoutingPreferences
    @Inject
    constructor(
        @TranscriptionRoutingDataStore private val dataStore: DataStore<Preferences>,
    ) : TranscriptionRoutingStore {
        override val selectedEngine: Flow<TranscriptionEngine> =
            dataStore.data.map { preferences ->
                TranscriptionEngine.fromId(preferences[SELECTED_ENGINE_KEY])
                    ?: TranscriptionEngine.LOCAL_PARAKEET
            }

        override suspend fun getSelectedEngine(): TranscriptionEngine = selectedEngine.first()

        override suspend fun setSelectedEngine(engine: TranscriptionEngine) {
            dataStore.edit { preferences ->
                preferences[SELECTED_ENGINE_KEY] = engine.id
            }
        }

        private companion object {
            val SELECTED_ENGINE_KEY = stringPreferencesKey("selected_transcription_engine")
        }
    }
