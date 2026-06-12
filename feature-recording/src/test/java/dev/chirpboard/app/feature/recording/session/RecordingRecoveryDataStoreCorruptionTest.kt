package dev.chirpboard.app.feature.recording.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.feature.recording.di.RecordingRecoveryModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Corruption resilience for the reliability-critical recording_recovery DataStore:
 * a corrupted preferences file must reset to safe defaults (never a permanently
 * throwing store), and defer-store reads must degrade to "nothing deferred" on IO
 * failure instead of taking the recovery flow down.
 */
class RecordingRecoveryDataStoreCorruptionTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @Test
    fun corruptedPreferencesFile_resetsToDefaultsInsteadOfThrowing() =
        runTest {
            val root = createTempDir("recovery-corruption-test")
            val file = File(root, "recording_recovery.preferences_pb")
            // Invalid protobuf wire format: parsing throws CorruptionException.
            file.writeBytes(byteArrayOf(-1, -1, -1, -1, -1))

            val dataStore =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = RecordingRecoveryModule.recoveryCorruptionHandler(),
                    produceFile = { file },
                )
            val deferStore = RecordingRecoveryDeferStore(dataStore)

            // Without the handler this read throws CorruptionException forever.
            assertEquals(emptySet<UUID>(), deferStore.loadDeferredSessionIds())

            // The store must be writable again after the reset.
            val sessionId = UUID.randomUUID()
            deferStore.deferSession(sessionId)
            assertEquals(setOf(sessionId), deferStore.loadDeferredSessionIds())
        }

    @Test
    fun corruptedPreferencesFile_protectedPathsStoreRecoversToEmpty() =
        runTest {
            val root = createTempDir("recovery-corruption-test")
            val file = File(root, "recording_recovery.preferences_pb")
            file.writeBytes(byteArrayOf(-1, -1, -1, -1, -1))

            val dataStore =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = RecordingRecoveryModule.recoveryCorruptionHandler(),
                    produceFile = { file },
                )
            val protectedPathsStore = RecordingRecoveryProtectedPathsStore(dataStore)

            val partition = protectedPathsStore.partitionProtectedPaths()
            assertTrue(partition.active.isEmpty())
            assertTrue(partition.expired.isEmpty())

            protectedPathsStore.protect(listOf("/audio/kept.m4a"))
            assertEquals(setOf("/audio/kept.m4a"), protectedPathsStore.activeProtectedPaths())
        }

    @Test
    fun loadDeferredSessionIds_returnsEmptyOnReadIoFailure() =
        runTest {
            val deferStore = RecordingRecoveryDeferStore(ThrowingDataStore())

            assertEquals(emptySet<UUID>(), deferStore.loadDeferredSessionIds())
        }

    @Test(expected = IOException::class)
    fun partitionProtectedPaths_failsClosedOnReadIoFailure() =
        runTest {
            val protectedPathsStore = RecordingRecoveryProtectedPathsStore(ThrowingDataStore())

            // Must propagate so the orphan cleaner aborts instead of treating
            // protected audio as unprotected.
            protectedPathsStore.partitionProtectedPaths()
        }

    private class ThrowingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("read failed") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("write failed")
    }
}
