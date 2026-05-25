package dev.chirpboard.app.feature.recording.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingRecoveryDeferStoreTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var deferStore: RecordingRecoveryDeferStore

    @Before
    fun setup() {
        val root = createTempDir("defer-store-test")
        dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(root, "recording_recovery.preferences_pb") },
            )
        deferStore = RecordingRecoveryDeferStore(dataStore)
    }

    @Test
    fun deferSession_persistsAcrossLoads() =
        runTest {
            val sessionId = UUID.randomUUID()

            deferStore.deferSession(sessionId)

            assertTrue(deferStore.loadDeferredSessionIds().contains(sessionId))
        }

    @Test
    fun retainOnly_removesIdsNotInPendingSet() =
        runTest {
            val kept = UUID.randomUUID()
            val removed = UUID.randomUUID()
            deferStore.deferSession(kept)
            deferStore.deferSession(removed)

            deferStore.retainOnly(setOf(kept))

            assertEquals(setOf(kept), deferStore.loadDeferredSessionIds())
        }
}
