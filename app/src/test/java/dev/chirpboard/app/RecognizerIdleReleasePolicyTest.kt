package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PRF-2: the bounded post-IME warm window and its reliability gates. The timer must fire only
 * after a full hidden-and-unused window, defer while anything is busy, stop when the recognizer
 * is gone, and never run again once cancelled by a release.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerIdleReleasePolicyTest {
    private val recordingStateManager = RecordingStateManager()
    private val repository = mockk<RecordingRepository>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        stubQueue(transcribing = emptyList(), pending = emptyList())
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun stubQueue(
        transcribing: List<Recording>,
        pending: List<Recording>,
        transcribingError: String? = null,
        pendingError: String? = null,
    ) {
        every { repository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns
            flowOf(RepositoryFlowState(transcribing, transcribingError))
        every { repository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns
            flowOf(RepositoryFlowState(pending, pendingError))
    }

    private fun newPolicy(): RecognizerIdleReleasePolicy =
        RecognizerIdleReleasePolicy(
            recordingStateManager = recordingStateManager,
            recordingRepository = dagger.Lazy { repository },
        )

    // region isExternallyBusy gates

    @Test
    fun `quiet system is not externally busy`() = runTest {
        assertFalse(newPolicy().isExternallyBusy())
    }

    @Test
    fun `any active capture makes the system busy - covers app, keyboard, widget, dialog`() =
        runTest {
            // Every capture surface must acquire the single global recording lock, so the
            // recording state is the one capture gate the release policy needs.
            recordingStateManager.tryStartRecording(RecordingOrigin.KEYBOARD)
            assertTrue(newPolicy().isExternallyBusy())
        }

    @Test
    fun `a recording mid-transcription makes the system busy`() = runTest {
        stubQueue(transcribing = listOf(mockk<Recording>(relaxed = true)), pending = emptyList())
        assertTrue(newPolicy().isExternallyBusy())
    }

    @Test
    fun `a recording pending transcription makes the system busy`() = runTest {
        stubQueue(transcribing = emptyList(), pending = listOf(mockk<Recording>(relaxed = true)))
        assertTrue(newPolicy().isExternallyBusy())
    }

    @Test
    fun `queue state that cannot be read counts as busy - fail toward keeping the model`() =
        runTest {
            stubQueue(
                transcribing = emptyList(),
                pending = emptyList(),
                transcribingError = "disk I/O error",
            )
            assertTrue(newPolicy().isExternallyBusy())
        }

    // endregion

    // region idle timer

    @Test
    fun `visible IME cancels idle release until a full grace window after hide`() = runTest {
        mockkObject(RecognizerManager)
        every { RecognizerManager.isResident() } returns true
        every { RecognizerManager.lastUsedAtMs() } returns 0L
        coEvery { RecognizerManager.releaseIfUnused(any(), any(), any()) } returns
            IdleReleaseDecision.RELEASE

        val policy = newPolicy()
        policy.scope = backgroundScope
        policy.clock = { testScheduler.currentTime }
        policy.start()
        policy.onImeVisibilityChanged(true)

        testScheduler.advanceTimeBy(policy.idleCutoffMs * 2)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { RecognizerManager.releaseIfUnused(any(), any(), any()) }

        policy.onImeVisibilityChanged(false)
        testScheduler.advanceTimeBy(policy.idleCutoffMs - 1)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { RecognizerManager.releaseIfUnused(any(), any(), any()) }

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        coVerify(exactly = 1) { RecognizerManager.releaseIfUnused(any(), any(), any()) }
    }

    @Test
    fun `pressure checks may release an idle model even while IME is visible`() = runTest {
        val policy = newPolicy()
        policy.onImeVisibilityChanged(true)

        assertTrue(policy.isExternallyBusy())
        assertFalse(policy.isExternallyBusy(protectVisibleIme = false))
    }

    @Test
    fun `timer releases exactly after a full unused window`() = runTest {
        mockkObject(RecognizerManager)
        every { RecognizerManager.isResident() } returns true
        every { RecognizerManager.lastUsedAtMs() } returns 0L
        coEvery { RecognizerManager.releaseIfUnused(any(), any(), any()) } returns
            IdleReleaseDecision.RELEASE

        val policy = newPolicy()
        policy.scope = backgroundScope
        policy.clock = { testScheduler.currentTime }
        policy.start()

        testScheduler.advanceTimeBy(policy.idleCutoffMs - 1)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { RecognizerManager.releaseIfUnused(any(), any(), any()) }

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        coVerify(exactly = 1) {
            RecognizerManager.releaseIfUnused(policy.idleCutoffMs, policy.idleCutoffMs, any())
        }
    }

    @Test
    fun `recent use pushes the timer out by recomputing from the recency stamp`() = runTest {
        mockkObject(RecognizerManager)
        var lastUsed = 0L
        every { RecognizerManager.isResident() } returns true
        every { RecognizerManager.lastUsedAtMs() } answers { lastUsed }
        coEvery { RecognizerManager.releaseIfUnused(any(), any(), any()) } returns
            IdleReleaseDecision.RELEASE

        val policy = newPolicy()
        policy.scope = backgroundScope
        policy.clock = { testScheduler.currentTime }
        policy.start()

        // The model is used shortly before the first window would have elapsed.
        testScheduler.advanceTimeBy(policy.idleCutoffMs - 1_000)
        testScheduler.runCurrent()
        lastUsed = testScheduler.currentTime

        // The original deadline passes without a release...
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { RecognizerManager.releaseIfUnused(any(), any(), any()) }

        // ...and the release fires one full window after the last use.
        testScheduler.advanceTimeBy(policy.idleCutoffMs - 1_000)
        testScheduler.runCurrent()
        coVerify(exactly = 1) { RecognizerManager.releaseIfUnused(any(), any(), any()) }
    }

    @Test
    fun `busy verdict defers the release and retries on the recheck cadence`() = runTest {
        mockkObject(RecognizerManager)
        every { RecognizerManager.isResident() } returns true
        every { RecognizerManager.lastUsedAtMs() } returns 0L
        coEvery { RecognizerManager.releaseIfUnused(any(), any(), any()) } returnsMany
            listOf(IdleReleaseDecision.EXTERNALLY_BUSY, IdleReleaseDecision.RELEASE)

        val policy = newPolicy()
        policy.scope = backgroundScope
        policy.clock = { testScheduler.currentTime }
        policy.start()

        testScheduler.advanceTimeBy(policy.idleCutoffMs)
        testScheduler.runCurrent()
        coVerify(exactly = 1) { RecognizerManager.releaseIfUnused(any(), any(), any()) }

        testScheduler.advanceTimeBy(policy.busyRecheckMs)
        testScheduler.runCurrent()
        coVerify(exactly = 2) { RecognizerManager.releaseIfUnused(any(), any(), any()) }
    }

    @Test
    fun `release callback cancels the timer so it never fires afterwards`() = runTest {
        mockkObject(RecognizerManager)
        every { RecognizerManager.isResident() } returns true
        every { RecognizerManager.lastUsedAtMs() } returns 0L
        coEvery { RecognizerManager.releaseIfUnused(any(), any(), any()) } returns
            IdleReleaseDecision.RELEASE

        val policy = newPolicy()
        policy.scope = backgroundScope
        policy.clock = { testScheduler.currentTime }
        policy.start()

        policy.onRecognizerReleased()
        testScheduler.advanceTimeBy(policy.idleCutoffMs * 3)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { RecognizerManager.releaseIfUnused(any(), any(), any()) }
    }

    @Test
    fun `timer does not arm while nothing is resident`() = runTest {
        mockkObject(RecognizerManager)
        every { RecognizerManager.isResident() } returns false
        coEvery { RecognizerManager.releaseIfUnused(any(), any(), any()) } returns
            IdleReleaseDecision.NOT_RESIDENT

        val policy = newPolicy()
        policy.scope = backgroundScope
        policy.clock = { testScheduler.currentTime }
        policy.start()

        testScheduler.advanceTimeBy(policy.idleCutoffMs * 2)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { RecognizerManager.releaseIfUnused(any(), any(), any()) }
    }

    // endregion

    @Test
    fun `pressure entry point uses a zero idle cutoff - not busy is sufficient`() = runTest {
        mockkObject(RecognizerManager)
        coEvery { RecognizerManager.releaseIfUnused(any(), any(), any()) } returns
            IdleReleaseDecision.RELEASE

        val policy = newPolicy()
        policy.clock = { 42L }
        val decision = policy.releaseNowIfUnused("test-pressure")

        assertEquals(IdleReleaseDecision.RELEASE, decision)
        coVerify(exactly = 1) { RecognizerManager.releaseIfUnused(0L, 42L, any()) }
    }
}
