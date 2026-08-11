package dev.chirpboard.app.core.recording

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class KeyboardPendingStopStoreTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: KeyboardPendingStopStore

    @Before
    fun setup() {
        val root = createTempDir("pending-stop-test")
        dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(root, "keyboard_pending_stop.preferences_pb") },
            )
        store = KeyboardPendingStopStore(dataStore)
    }

    @Test
    fun enqueueAndPeek_returnsPendingStop() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            val pending = store.peek()

            assertEquals(RecordingOrigin.WIDGET, pending?.requesterOrigin)
        }

    @Test
    fun clear_removesPendingStop() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)
            store.clear()

            assertNull(store.peek())
        }

    @Test
    fun peek_withinTtl_returnsPendingStop() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            val pending =
                store.peek(
                    nowEpochMs = System.currentTimeMillis() + KeyboardPendingStopStore.PENDING_STOP_TTL_MS - 1_000L,
                )

            assertEquals(RecordingOrigin.WIDGET, pending?.requesterOrigin)
        }

    @Test
    fun peek_expiredEntryIsIgnoredAndCleared() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            val expired =
                store.peek(
                    nowEpochMs = System.currentTimeMillis() + KeyboardPendingStopStore.PENDING_STOP_TTL_MS + 1_000L,
                )

            assertNull(expired)
            // The expired entry is also removed so it can never fire later.
            assertNull(store.peek())
        }

    @Test
    fun peek_entryTimestampedInTheFutureIsIgnoredAndCleared() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            // The clock moved backwards after the write, so the entry looks like it was made
            // in the future. It must age out rather than live forever.
            val skewed = store.peek(nowEpochMs = System.currentTimeMillis() - 60 * 60 * 1000L)

            assertNull(skewed)
            assertNull(store.peek())
        }

    @Test
    fun reconcileStale_clearsWhenGlobalStateIdle() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            store.reconcileStale(RecordingState.Idle)

            assertNull(store.peek())
        }

    @Test
    fun reconcileStale_clearsWhenAppRecordingActive() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            store.reconcileStale(
                RecordingState.Recording(
                    origin = RecordingOrigin.APP,
                    recordingId = java.util.UUID.randomUUID(),
                ),
            )

            assertNull(store.peek())
        }

    @Test
    fun reconcileStale_retainsWhenKeyboardRecordingActive() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            store.reconcileStale(
                RecordingState.Recording(
                    origin = RecordingOrigin.KEYBOARD,
                    recordingId = java.util.UUID.randomUUID(),
                ),
            )

            assertEquals(RecordingOrigin.WIDGET, store.peek()?.requesterOrigin)
        }

    @Test
    fun reconcileStale_retainsWhenKeyboardStopping() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            store.reconcileStale(
                RecordingState.Stopping(
                    origin = RecordingOrigin.KEYBOARD,
                    recordingId = java.util.UUID.randomUUID(),
                ),
            )

            assertEquals(RecordingOrigin.WIDGET, store.peek()?.requesterOrigin)
        }

    @Test
    fun reconcileStale_clearsWhenKeyboardError() =
        runTest {
            store.enqueue(RecordingOrigin.WIDGET)

            store.reconcileStale(
                RecordingState.Error(
                    origin = RecordingOrigin.KEYBOARD,
                    message = "failed",
                ),
            )

            assertNull(store.peek())
        }

    @Test
    fun shouldRetainPendingStop_retainsKeyboardStoppingOnlyForKeyboardOrigin() {
        assertEquals(
            true,
            store.shouldRetainPendingStop(
                RecordingState.Stopping(
                    origin = RecordingOrigin.KEYBOARD,
                    recordingId = java.util.UUID.randomUUID(),
                ),
            ),
        )
        assertEquals(
            false,
            store.shouldRetainPendingStop(
                RecordingState.Stopping(
                    origin = RecordingOrigin.APP,
                    recordingId = java.util.UUID.randomUUID(),
                ),
            ),
        )
    }
}
