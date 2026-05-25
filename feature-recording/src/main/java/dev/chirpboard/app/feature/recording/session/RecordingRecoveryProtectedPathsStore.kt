package dev.chirpboard.app.feature.recording.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.feature.recording.di.RecordingRecoveryDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class RecordingRecoveryProtectedPathsStore
    @Inject
    constructor(
        @RecordingRecoveryDataStore private val dataStore: DataStore<Preferences>,
    ) {
        suspend fun protect(
            paths: Collection<String>,
            ttlMs: Long = DEFAULT_TTL_MS,
        ) {
            if (paths.isEmpty()) {
                return
            }
            val expiresAt = System.currentTimeMillis() + ttlMs
            dataStore.edit { preferences ->
                val current = decode(preferences[PROTECTED_PATHS_KEY].orEmpty())
                val updated =
                    current.toMutableMap().apply {
                        paths.forEach { path -> put(path, expiresAt) }
                    }
                preferences[PROTECTED_PATHS_KEY] = encode(updated)
            }
        }

        suspend fun activeProtectedPaths(): Set<String> {
            val now = System.currentTimeMillis()
            val (active, expired) = partition(now, decode(dataStore.data.first()[PROTECTED_PATHS_KEY].orEmpty()))
            if (expired.isNotEmpty()) {
                dataStore.edit { preferences ->
                    preferences[PROTECTED_PATHS_KEY] = encode(active)
                }
            }
            return active.keys
        }

        private fun encode(entries: Map<String, Long>): String =
            entries.entries.joinToString(",") { (path, expiresAt) ->
                "${encodePath(path)}|$expiresAt"
            }

        private fun decode(raw: String): Map<String, Long> =
            raw
                .split(',')
                .mapNotNull { token ->
                    val parts = token.split('|', limit = 2)
                    if (parts.size != 2) {
                        return@mapNotNull null
                    }
                    val expiresAt = parts[1].toLongOrNull() ?: return@mapNotNull null
                    decodePath(parts[0]) to expiresAt
                }.toMap()

        private fun partition(
            now: Long,
            entries: Map<String, Long>,
        ): Pair<Map<String, Long>, Map<String, Long>> {
            val active = entries.filterValues { expiresAt -> expiresAt > now }
            val expired = entries.filterValues { expiresAt -> expiresAt <= now }
            return active to expired
        }

        private fun encodePath(path: String): String = path.replace(",", "%2C").replace("|", "%7C")

        private fun decodePath(encoded: String): String =
            encoded.replace("%7C", "|").replace("%2C", ",")

        companion object {
            const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
            private val PROTECTED_PATHS_KEY = stringPreferencesKey("protected_audio_paths")
        }
    }
