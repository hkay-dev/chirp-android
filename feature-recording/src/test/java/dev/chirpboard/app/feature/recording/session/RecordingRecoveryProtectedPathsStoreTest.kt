package dev.chirpboard.app.feature.recording.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class RecordingRecoveryProtectedPathsStoreTest {
    private lateinit var store: RecordingRecoveryProtectedPathsStore

    @Before
    fun setup() {
        val root = createTempDir("protected-paths-test")
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(root, "recording_recovery.preferences_pb") },
            )
        store = RecordingRecoveryProtectedPathsStore(dataStore)
    }

    @Test
    fun protect_pathsAreActiveUntilTtlLapses() =
        runTest {
            store.protect(listOf("/tmp/kept.m4a"))

            assertEquals(setOf("/tmp/kept.m4a"), store.activeProtectedPaths())
            assertEquals(emptySet<String>(), store.partitionProtectedPaths().expired)
            assertEquals(setOf("/tmp/kept.m4a"), store.activeProtectedPaths())
        }

    @Test
    fun partitionProtectedPaths_reportsExpiredWithoutRemovingMarkers() =
        runTest {
            store.protect(listOf("/tmp/expired.m4a"), ttlMs = -1L)
            store.protect(listOf("/tmp/active.m4a"))

            val partition = store.partitionProtectedPaths()

            assertEquals(setOf("/tmp/expired.m4a"), partition.expired)
            assertEquals(setOf("/tmp/active.m4a"), partition.active)
            // Listing must not consume the marker: a crash before the file is durably
            // quarantined would otherwise downgrade the next run to hard deletion.
            assertEquals(setOf("/tmp/expired.m4a"), store.partitionProtectedPaths().expired)
        }

    @Test
    fun clearPaths_removesOnlyTheHandledMarkers() =
        runTest {
            store.protect(listOf("/tmp/expired.m4a"), ttlMs = -1L)
            store.protect(listOf("/tmp/fresh-keep.m4a"))

            store.clearPaths(listOf("/tmp/expired.m4a"))

            val partition = store.partitionProtectedPaths()
            assertEquals(emptySet<String>(), partition.expired)
            assertEquals(setOf("/tmp/fresh-keep.m4a"), partition.active)
        }

    @Test
    fun clearPaths_ignoresUnknownPaths() =
        runTest {
            store.protect(listOf("/tmp/fresh-keep.m4a"))

            store.clearPaths(listOf("/tmp/never-protected.m4a"))
            store.clearPaths(emptyList())

            assertEquals(setOf("/tmp/fresh-keep.m4a"), store.activeProtectedPaths())
        }
}
