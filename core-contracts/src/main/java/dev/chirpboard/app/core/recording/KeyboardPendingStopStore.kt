package dev.chirpboard.app.core.recording

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.core.di.KeyboardPendingStopDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class KeyboardPendingStopStore
    @Inject
    constructor(
        @KeyboardPendingStopDataStore private val dataStore: DataStore<Preferences>,
    ) {
        suspend fun enqueue(requesterOrigin: RecordingOrigin) {
            dataStore.edit { preferences ->
                preferences[REQUESTED_AT_KEY] = System.currentTimeMillis()
                preferences[REQUESTER_ORIGIN_KEY] = requesterOrigin.name
            }
        }

        suspend fun peek(): PendingKeyboardStop? {
            val preferences = dataStore.data.first()
            val requestedAt = preferences[REQUESTED_AT_KEY] ?: return null
            val originName = preferences[REQUESTER_ORIGIN_KEY] ?: return null
            val origin = runCatching { RecordingOrigin.valueOf(originName) }.getOrNull() ?: return null
            return PendingKeyboardStop(
                requestedAtEpochMs = requestedAt,
                requesterOrigin = origin,
            )
        }

        suspend fun clear() {
            dataStore.edit { preferences ->
                preferences.remove(REQUESTED_AT_KEY)
                preferences.remove(REQUESTER_ORIGIN_KEY)
            }
        }

        suspend fun reconcileStale(state: RecordingState) {
            if (peek() == null) {
                return
            }
            if (shouldRetainPendingStop(state)) {
                return
            }
            clear()
        }

        internal fun shouldRetainPendingStop(state: RecordingState): Boolean =
            when {
                state is RecordingState.Stopping && state.origin == RecordingOrigin.KEYBOARD -> true
                state.activeOrigin == RecordingOrigin.KEYBOARD &&
                    (
                        state is RecordingState.Starting ||
                            state is RecordingState.Recording ||
                            state is RecordingState.Paused
                    ) -> true
                else -> false
            }

        companion object {
            private val REQUESTED_AT_KEY = longPreferencesKey("requested_at_epoch_ms")
            private val REQUESTER_ORIGIN_KEY = stringPreferencesKey("requester_origin")
        }
    }
