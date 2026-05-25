package dev.chirpboard.app.feature.recording.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.feature.recording.di.RecordingRecoveryDataStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class RecordingRecoveryDeferStore
    @Inject
    constructor(
        @RecordingRecoveryDataStore private val dataStore: DataStore<Preferences>,
    ) {
        suspend fun loadDeferredSessionIds(): Set<UUID> {
            val raw = dataStore.data.first()[DEFERRED_SESSION_IDS_KEY].orEmpty()
            return decode(raw)
        }

        suspend fun deferSession(sessionId: UUID) {
            dataStore.edit { preferences ->
                val current = decode(preferences[DEFERRED_SESSION_IDS_KEY].orEmpty())
                preferences[DEFERRED_SESSION_IDS_KEY] = encode(current + sessionId)
            }
        }

        suspend fun retainOnly(sessionIds: Set<UUID>) {
            dataStore.edit { preferences ->
                val current = decode(preferences[DEFERRED_SESSION_IDS_KEY].orEmpty())
                preferences[DEFERRED_SESSION_IDS_KEY] = encode(current.intersect(sessionIds))
            }
        }

        private fun encode(sessionIds: Set<UUID>): String = sessionIds.joinToString(",") { it.toString() }

        private fun decode(raw: String): Set<UUID> =
            raw
                .split(',')
                .mapNotNull { value ->
                    value.trim().takeIf(String::isNotEmpty)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                }.toSet()

        companion object {
            private val DEFERRED_SESSION_IDS_KEY = stringPreferencesKey("deferred_session_ids")
        }
    }
