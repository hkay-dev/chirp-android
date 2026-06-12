package dev.chirpboard.app.feature.recording.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.feature.recording.di.RecordingRecoveryDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class ProtectedPathsPartition(
    val active: Set<String>,
    val expired: Set<String>,
)

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

        suspend fun activeProtectedPaths(): Set<String> = partitionProtectedPaths().active

        /**
         * Single-snapshot partition of protected paths into still-active and TTL-expired
         * sets, so a TTL lapsing between two separate reads can never put a path in both
         * (or neither) set. Nothing is removed here: callers must [clearPaths] only after
         * the underlying audio has been durably quarantined or deleted, so a crash or a
         * failed rename keeps the marker and the next run retries quarantine instead of
         * hard-deleting kept audio.
         *
         * Deliberately NOT wrapped in a defensive catch: a read failure must propagate so
         * the orphan cleaner aborts (fail closed). Swallowing to an empty set would make
         * protected audio look unprotected and eligible for deletion. The store-level
         * corruption handler already converts persistent corruption into safe defaults.
         */
        suspend fun partitionProtectedPaths(): ProtectedPathsPartition {
            val now = System.currentTimeMillis()
            val (active, expired) = partition(now, decode(dataStore.data.first()[PROTECTED_PATHS_KEY].orEmpty()))
            return ProtectedPathsPartition(active = active.keys, expired = expired.keys)
        }

        /**
         * Removes protection markers for paths whose audio has been durably handled.
         * The removal runs inside an edit transform, so DataStore serializes it against
         * concurrent [protect] calls and neither write can clobber the other.
         */
        suspend fun clearPaths(paths: Collection<String>) {
            if (paths.isEmpty()) {
                return
            }
            val cleared = paths.toSet()
            dataStore.edit { preferences ->
                val current = decode(preferences[PROTECTED_PATHS_KEY].orEmpty())
                val updated = current - cleared
                if (updated.size != current.size) {
                    preferences[PROTECTED_PATHS_KEY] = encode(updated)
                }
            }
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
