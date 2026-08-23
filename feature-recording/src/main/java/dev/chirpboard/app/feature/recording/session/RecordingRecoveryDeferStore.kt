package dev.chirpboard.app.feature.recording.session

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.feature.recording.di.RecordingRecoveryDataStore
import java.io.IOException
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
            // Defensive: an IO failure reading the recovery store must never take the
            // recovery flow down with it. Treating nothing as deferred is the safe
            // direction — every pending session becomes actionable again, so the user
            // sees recovery prompts rather than silently losing them.
            val raw =
                try {
                    dataStore.data.first()[DEFERRED_SESSION_IDS_KEY].orEmpty()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to read deferred session ids; treating none as deferred", e)
                    return emptySet()
                }
            return decode(raw)
        }

        suspend fun deferSession(sessionId: UUID) {
            // Same defensive posture as the reader: a failed write means the session stays
            // actionable, which only re-shows a recovery prompt — never a crash.
            try {
                dataStore.edit { preferences ->
                    val current = decode(preferences[DEFERRED_SESSION_IDS_KEY].orEmpty())
                    preferences[DEFERRED_SESSION_IDS_KEY] = encode(current + sessionId)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to persist deferred session id", e)
            }
        }

        suspend fun retainOnly(sessionIds: Set<UUID>) {
            // A failed prune only leaves stale ids behind; they are re-pruned on the next
            // refresh, and this is reached from HomeViewModel.init with no handler above it.
            try {
                dataStore.edit { preferences ->
                    val current = decode(preferences[DEFERRED_SESSION_IDS_KEY].orEmpty())
                    preferences[DEFERRED_SESSION_IDS_KEY] = encode(current.intersect(sessionIds))
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to prune deferred session ids", e)
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
            private const val TAG = "RecoveryDeferStore"
            private val DEFERRED_SESSION_IDS_KEY = stringPreferencesKey("deferred_session_ids")
        }
    }
