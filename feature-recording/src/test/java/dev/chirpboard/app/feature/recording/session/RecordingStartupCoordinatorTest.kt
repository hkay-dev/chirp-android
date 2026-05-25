package dev.chirpboard.app.feature.recording.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.recording.cleanup.OrphanedAudioCleaner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class RecordingStartupCoordinatorTest {
    private lateinit var recoveryStore: RecordingRecoveryStore
    private lateinit var orphanedAudioCleaner: OrphanedAudioCleaner
    private lateinit var sessionJournal: RecordingSessionJournal
    private lateinit var pendingStopStore: KeyboardPendingStopStore
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var coordinator: RecordingStartupCoordinator

    @Before
    fun setup() {
        val root = createTempDir("startup-coordinator-test")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { File(root, "keyboard_pending_stop.preferences_pb") },
            )
        pendingStopStore = KeyboardPendingStopStore(dataStore)
        recoveryStore = mockk(relaxed = true)
        orphanedAudioCleaner = mockk(relaxed = true)
        sessionJournal = mockk(relaxed = true)
        recordingStateManager = mockk(relaxed = true)
        val finalizeStartupReconciler = mockk<RecordingFinalizeStartupReconciler>(relaxed = true)
        coEvery { recoveryStore.refresh() } returns Unit
        coEvery { finalizeStartupReconciler.reconcilePendingFinalizations() } returns Unit
        coordinator =
            RecordingStartupCoordinator(
                recoveryStore = recoveryStore,
                orphanedAudioCleaner = orphanedAudioCleaner,
                sessionJournal = sessionJournal,
                pendingStopStore = pendingStopStore,
                recordingStateManager = recordingStateManager,
                finalizeStartupReconciler = finalizeStartupReconciler,
            )
    }

    @Test
    fun onAppStart_retainsPendingStopDuringKeyboardStopping() =
        runTest {
            pendingStopStore.enqueue(RecordingOrigin.WIDGET)
            every { recordingStateManager.state } returns
                MutableStateFlow(
                    RecordingState.Stopping(
                        origin = RecordingOrigin.KEYBOARD,
                        recordingId = UUID.randomUUID(),
                    ),
                )

            coordinator.onAppStart()

            assertNotNull(pendingStopStore.peek())
        }

    @Test
    fun onAppStart_clearsPendingStopWhenIdle() =
        runTest {
            pendingStopStore.enqueue(RecordingOrigin.WIDGET)
            every { recordingStateManager.state } returns MutableStateFlow(RecordingState.Idle)

            coordinator.onAppStart()

            assertEquals(null, pendingStopStore.peek())
        }
}
