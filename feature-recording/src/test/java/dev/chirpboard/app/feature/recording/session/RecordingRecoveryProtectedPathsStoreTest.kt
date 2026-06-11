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
            assertEquals(emptySet<String>(), store.consumeExpiredPaths())
            assertEquals(setOf("/tmp/kept.m4a"), store.activeProtectedPaths())
        }

    @Test
    fun consumeExpiredPaths_removesExpiredAndKeepsActiveEntries() =
        runTest {
            store.protect(listOf("/tmp/expired.m4a"), ttlMs = -1L)
            store.protect(listOf("/tmp/active.m4a"))

            val expired = store.consumeExpiredPaths()

            assertEquals(setOf("/tmp/expired.m4a"), expired)
            assertEquals(setOf("/tmp/active.m4a"), store.activeProtectedPaths())
            // A second pass must not report (or remove) anything else.
            assertEquals(emptySet<String>(), store.consumeExpiredPaths())
            assertEquals(setOf("/tmp/active.m4a"), store.activeProtectedPaths())
        }

    @Test
    fun consumeExpiredPaths_retainsProtectionAppliedAfterPartition() =
        runTest {
            // The partition happens inside the DataStore edit transform, so an entry
            // written by protect() can never be clobbered by a stale outside-the-edit
            // snapshot: whatever protect() committed before the consume edit runs is
            // re-read inside the transform.
            store.protect(listOf("/tmp/expired.m4a"), ttlMs = -1L)
            store.protect(listOf("/tmp/fresh-keep.m4a"))

            assertEquals(setOf("/tmp/expired.m4a"), store.consumeExpiredPaths())
            assertEquals(setOf("/tmp/fresh-keep.m4a"), store.activeProtectedPaths())
        }
}
