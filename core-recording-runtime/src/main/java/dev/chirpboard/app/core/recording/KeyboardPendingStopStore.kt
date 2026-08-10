package dev.chirpboard.app.core.recording

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.core.di.KeyboardPendingStopDataStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@Singleton
class KeyboardPendingStopStore
    @Inject
    constructor(
        @KeyboardPendingStopDataStore private val dataStore: DataStore<Preferences>,
    ) {
        /**
         * @return true when the pending stop was durably written. The corruption handler only
         *   covers CorruptionException, so a plain disk IOException surfaces here; swallowing
         *   it (and reporting the failure) beats crashing the caller's scope in the IME process.
         */
        suspend fun enqueue(requesterOrigin: RecordingOrigin): Boolean =
            try {
                dataStore.edit { preferences ->
                    preferences[REQUESTED_AT_KEY] = System.currentTimeMillis()
                    preferences[REQUESTER_ORIGIN_KEY] = requesterOrigin.name
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Failed to persist pending keyboard stop", e)
                false
            }

        suspend fun peek(nowEpochMs: Long = System.currentTimeMillis()): PendingKeyboardStop? {
            val preferences =
                try {
                    dataStore.data.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to read pending keyboard stop", e)
                    return null
                }
            val requestedAt = preferences[REQUESTED_AT_KEY] ?: return null
            val originName = preferences[REQUESTER_ORIGIN_KEY] ?: return null
            val origin = runCatching { RecordingOrigin.valueOf(originName) }.getOrNull() ?: return null
            if (nowEpochMs - requestedAt > PENDING_STOP_TTL_MS) {
                // A stop request this old can only clobber a newer healthy session;
                // drop it instead of letting it fire long after the fact.
                clear()
                return null
            }
            return PendingKeyboardStop(
                requestedAtEpochMs = requestedAt,
                requesterOrigin = origin,
            )
        }

        suspend fun clear() {
            try {
                dataStore.edit { preferences ->
                    preferences.remove(REQUESTED_AT_KEY)
                    preferences.remove(REQUESTER_ORIGIN_KEY)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // The TTL bounds a pending stop that could not be cleared.
                Log.e(TAG, "Failed to clear pending keyboard stop", e)
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
            private const val TAG = "KeyboardPendingStop"
            private val REQUESTED_AT_KEY = longPreferencesKey("requested_at_epoch_ms")
            private val REQUESTER_ORIGIN_KEY = stringPreferencesKey("requester_origin")

            /** Pending stops older than this are stale and must never fire. */
            internal const val PENDING_STOP_TTL_MS = 2 * 60 * 1000L
        }
    }
