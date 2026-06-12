package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRF-2 reliability gating matrix: the recognizer must NEVER be released while any
 * capture/transcription surface could need it synchronously. Every gate is exercised
 * individually and in combination, for both the idle path (30-min cutoff) and the pressure
 * path (cutoff 0 — "not busy" is sufficient).
 */
class RecognizerIdleReleaseDecisionTest {
    private companion object {
        const val CUTOFF = 30 * 60_000L
        const val NOW = 10_000_000L
    }

    private fun decide(
        isResident: Boolean = true,
        activeLeases: Int = 0,
        lastUsedAtMs: Long = NOW - CUTOFF,
        minIdleMs: Long = CUTOFF,
        isExternallyBusy: Boolean = false,
    ): IdleReleaseDecision =
        idleReleaseDecision(
            isResident = isResident,
            activeLeases = activeLeases,
            lastUsedAtMs = lastUsedAtMs,
            nowMs = NOW,
            minIdleMs = minIdleMs,
            isExternallyBusy = isExternallyBusy,
        )

    @Test
    fun `releases when resident, unleased, externally quiet, and idle past the cutoff`() {
        assertEquals(IdleReleaseDecision.RELEASE, decide())
    }

    @Test
    fun `not resident is a no-op regardless of other gates`() {
        assertEquals(IdleReleaseDecision.NOT_RESIDENT, decide(isResident = false))
        assertEquals(
            IdleReleaseDecision.NOT_RESIDENT,
            decide(isResident = false, activeLeases = 3, isExternallyBusy = true),
        )
    }

    @Test
    fun `any in-flight usage lease blocks the release`() {
        assertEquals(IdleReleaseDecision.IN_USE, decide(activeLeases = 1))
        assertEquals(IdleReleaseDecision.IN_USE, decide(activeLeases = 5))
    }

    @Test
    fun `an in-flight lease blocks even the zero-cutoff pressure path`() {
        assertEquals(IdleReleaseDecision.IN_USE, decide(activeLeases = 1, minIdleMs = 0L))
    }

    @Test
    fun `external capture or queue activity blocks the release`() {
        assertEquals(IdleReleaseDecision.EXTERNALLY_BUSY, decide(isExternallyBusy = true))
    }

    @Test
    fun `external activity blocks even the zero-cutoff pressure path`() {
        assertEquals(
            IdleReleaseDecision.EXTERNALLY_BUSY,
            decide(isExternallyBusy = true, minIdleMs = 0L),
        )
    }

    @Test
    fun `recent use blocks the idle path`() {
        assertEquals(
            IdleReleaseDecision.RECENTLY_USED,
            decide(lastUsedAtMs = NOW - CUTOFF + 1),
        )
        assertEquals(IdleReleaseDecision.RECENTLY_USED, decide(lastUsedAtMs = NOW))
    }

    @Test
    fun `exactly-at-cutoff counts as idle`() {
        assertEquals(IdleReleaseDecision.RELEASE, decide(lastUsedAtMs = NOW - CUTOFF))
    }

    @Test
    fun `pressure path ignores recency when otherwise quiet`() {
        assertEquals(
            IdleReleaseDecision.RELEASE,
            decide(lastUsedAtMs = NOW, minIdleMs = 0L),
        )
    }

    @Test
    fun `lease gate outranks the external gate so the cause is the closest one to the model`() {
        assertEquals(
            IdleReleaseDecision.IN_USE,
            decide(activeLeases = 1, isExternallyBusy = true),
        )
    }

    @Test
    fun `never-used resident recognizer with zero stamp still respects the idle window`() {
        // lastUsedAtMs == 0 only happens before any touch; with a fresh process NOW is large,
        // so the window is already elapsed — releasing an untouched model is safe by definition.
        assertEquals(IdleReleaseDecision.RELEASE, decide(lastUsedAtMs = 0L))
    }
}
