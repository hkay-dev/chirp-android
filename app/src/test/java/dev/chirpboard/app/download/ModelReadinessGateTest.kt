package dev.chirpboard.app.download

import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.modelreadiness.ModelReadyResult
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelReadinessGateTest {
    private lateinit var speechModelStore: SpeechModelStore
    private lateinit var gate: ModelReadinessGate
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        speechModelStore = mockk()
        gate =
            ModelReadinessGate(
                speechModelStore = speechModelStore,
                ioDispatcher = testDispatcher,
                now = { 1000L },
                gateScope = testScope,
            )
    }

    @Test
    fun `warmupIfNeeded calls ensureReady when state is Unknown`() =
        testScope.runTest {
            coEvery { speechModelStore.evaluateReadiness() } returns
                ModelReadinessEvaluation(
                    isReady = true,
                    verificationSource = ModelReadinessVerificationSource.PROCESS_CACHE,
                )

            gate.warmupIfNeeded(VerificationTrigger.APP_STARTUP)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = gate.state.value
            assertTrue(state is ModelReadinessState.Ready)
            assertEquals(ModelReadinessVerificationSource.PROCESS_CACHE, (state as ModelReadinessState.Ready).source)

            coVerify(exactly = 1) { speechModelStore.evaluateReadiness() }
        }

    @Test
    fun `warmupIfNeeded does nothing if state is already Checking or Ready`() =
        testScope.runTest {
            coEvery { speechModelStore.evaluateReadiness() } returns ModelReadinessEvaluation(isReady = true)

            gate.warmupIfNeeded()
            testDispatcher.scheduler.advanceUntilIdle()

            // Second call should do nothing
            gate.warmupIfNeeded()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { speechModelStore.evaluateReadiness() }
        }

    @Test
    fun `ensureReady returns Ready if verifier is successful`() =
        testScope.runTest {
            coEvery { speechModelStore.evaluateReadiness() } returns
                ModelReadinessEvaluation(
                    isReady = true,
                    verificationSource = ModelReadinessVerificationSource.CHECKSUM_VERIFICATION,
                )

            val result = gate.ensureReady(VerificationTrigger.HOME_VISIBLE)

            assertTrue(result is ModelReadyResult.Ready)
            assertEquals(ModelReadinessVerificationSource.CHECKSUM_VERIFICATION, (result as ModelReadyResult.Ready).source)
        }

    @Test
    fun `ensureReady deduplicates concurrent verifications`() =
        testScope.runTest {
            coEvery { speechModelStore.evaluateReadiness() } coAnswers {
                delay(100)
                ModelReadinessEvaluation(true, ModelReadinessVerificationSource.CHECKSUM_VERIFICATION)
            }

            var result1: ModelReadyResult? = null
            var result2: ModelReadyResult? = null

            val job1 =
                launch {
                    result1 = gate.ensureReady(VerificationTrigger.APP_STARTUP)
                }
            val job2 =
                launch {
                    result2 = gate.ensureReady(VerificationTrigger.HOME_RECORD_TAP)
                }

            testDispatcher.scheduler.advanceUntilIdle()
            job1.join()
            job2.join()

            assertTrue(result1 is ModelReadyResult.Ready)
            assertEquals(result1, result2)
            coVerify(exactly = 1) { speechModelStore.evaluateReadiness() }
        }

    @Test
    fun `ensureReady handles verifier returning false`() =
        testScope.runTest {
            coEvery { speechModelStore.evaluateReadiness() } returns
                ModelReadinessEvaluation(
                    isReady = false,
                    unavailableReason = ModelReadinessUnavailableReason.MISSING_MODEL_FILES,
                )

            val result = gate.ensureReady(VerificationTrigger.APP_STARTUP)

            assertTrue(result is ModelReadyResult.Unavailable)
            assertEquals(ModelReadinessUnavailableReason.MISSING_MODEL_FILES, (result as ModelReadyResult.Unavailable).reason)

            val state = gate.state.value
            assertTrue(state is ModelReadinessState.Unavailable)
            assertEquals(ModelReadinessUnavailableReason.MISSING_MODEL_FILES, (state as ModelReadinessState.Unavailable).reason)
        }

    @Test
    fun `ensureReady handles verifier exception`() =
        testScope.runTest {
            coEvery { speechModelStore.evaluateReadiness() } throws RuntimeException("Verification crashed")

            val result = gate.ensureReady(VerificationTrigger.APP_STARTUP)

            assertTrue(result is ModelReadyResult.Error)
            assertEquals("Verification crashed", (result as ModelReadyResult.Error).message)

            val state = gate.state.value
            assertTrue(state is ModelReadinessState.Error)
        }
}
