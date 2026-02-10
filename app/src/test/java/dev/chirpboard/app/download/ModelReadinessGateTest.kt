package dev.chirpboard.app.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelReadinessGateTest {

    @Test
    fun ensureReady_readyEvaluation_updatesStateAndReturnsReady() = runTest {
        val gate = ModelReadinessGate(
            verifier = ModelReadinessVerifier {
                ModelReadinessEvaluation(
                    isReady = true,
                    verificationSource = ModelReadinessVerificationSource.CHECKSUM_VERIFICATION
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            now = { 1_000L },
            gateScope = this
        )

        val result = gate.ensureReady(VerificationTrigger.HOME_RECORD_TAP)

        assertTrue(result is ModelReadyResult.Ready)
        assertTrue(gate.state.value is ModelReadinessState.Ready)
    }

    @Test
    fun ensureReady_setsCheckingStateImmediately() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val gate = ModelReadinessGate(
            verifier = ModelReadinessVerifier {
                blocker.await()
                ModelReadinessEvaluation(
                    isReady = true,
                    verificationSource = ModelReadinessVerificationSource.CHECKSUM_VERIFICATION
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            now = { 2_000L },
            gateScope = this
        )

        val pending = async { gate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) }
        runCurrent()

        assertTrue(gate.state.value is ModelReadinessState.Checking)

        blocker.complete(Unit)
        advanceUntilIdle()

        assertTrue(pending.await() is ModelReadyResult.Ready)
    }

    @Test
    fun ensureReady_concurrentCallsShareSingleVerification() = runTest {
        var verifierCalls = 0
        val blocker = CompletableDeferred<Unit>()
        val gate = ModelReadinessGate(
            verifier = ModelReadinessVerifier {
                verifierCalls += 1
                blocker.await()
                ModelReadinessEvaluation(
                    isReady = true,
                    verificationSource = ModelReadinessVerificationSource.CHECKSUM_VERIFICATION
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            gateScope = this
        )

        val first = async { gate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) }
        val second = async { gate.ensureReady(VerificationTrigger.HOME_VISIBLE) }
        runCurrent()

        assertEquals(1, verifierCalls)

        blocker.complete(Unit)
        advanceUntilIdle()

        assertTrue(first.await() is ModelReadyResult.Ready)
        assertTrue(second.await() is ModelReadyResult.Ready)
        assertEquals(1, verifierCalls)
    }

    @Test
    fun ensureReady_unavailableEvaluation_surfacesIntegrityMismatch() = runTest {
        val gate = ModelReadinessGate(
            verifier = ModelReadinessVerifier {
                ModelReadinessEvaluation(
                    isReady = false,
                    unavailableReason = ModelReadinessUnavailableReason.INTEGRITY_MISMATCH
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            gateScope = this
        )

        val result = gate.ensureReady(VerificationTrigger.HOME_RECORD_TAP)

        assertTrue(result is ModelReadyResult.Unavailable)
        val unavailable = result as ModelReadyResult.Unavailable
        assertEquals(ModelReadinessUnavailableReason.INTEGRITY_MISMATCH, unavailable.reason)
        assertTrue(gate.state.value is ModelReadinessState.Unavailable)
    }

    @Test
    fun warmupIfNeeded_onlyRunsFromUnknownState() = runTest {
        var verifierCalls = 0
        val gate = ModelReadinessGate(
            verifier = ModelReadinessVerifier {
                verifierCalls += 1
                ModelReadinessEvaluation(
                    isReady = true,
                    verificationSource = ModelReadinessVerificationSource.CHECKSUM_VERIFICATION
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            gateScope = this
        )

        gate.warmupIfNeeded(VerificationTrigger.APP_STARTUP)
        advanceUntilIdle()
        gate.warmupIfNeeded(VerificationTrigger.HOME_VISIBLE)
        advanceUntilIdle()

        assertEquals(1, verifierCalls)
    }
}
