package dev.chirpboard.app.core.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationReliabilityMetricsTest {
    @Test
    fun `commit refusal cannot escape an active soak`() {
        DictationReliabilityMetrics.clear()
        DictationReliabilityMetrics.record(DictationReliabilityMetric.COMMIT, 500, success = false)
        DictationReliabilityMetrics.armCommitRefusal()
        assertFalse(DictationReliabilityMetrics.consumeCommitRefusal())

        DictationReliabilityMetrics.startSoak(targetSessions = 2)
        assertTrue(DictationReliabilityMetrics.snapshot.value.summaries.isEmpty())
        DictationReliabilityMetrics.armCommitRefusal()
        assertTrue(DictationReliabilityMetrics.snapshot.value.soak.refuseNextCommit)
        DictationReliabilityMetrics.stopSoak()

        assertFalse(DictationReliabilityMetrics.consumeCommitRefusal())
        assertFalse(DictationReliabilityMetrics.snapshot.value.soak.refuseNextCommit)
        DictationReliabilityMetrics.clear()
    }

    @Test
    fun `rolling series keeps its newest bounded samples`() {
        val series = DictationReliabilitySeries(capacity = 3)
        series.add(DictationReliabilityMetric.PRESS_TO_AUDIO, 10, success = true)
        series.add(DictationReliabilityMetric.PRESS_TO_AUDIO, 20, success = true)
        series.add(DictationReliabilityMetric.PRESS_TO_AUDIO, 30, success = true)
        series.add(DictationReliabilityMetric.PRESS_TO_AUDIO, 40, success = false)

        assertEquals(listOf(20L, 30L, 40L), series.values(DictationReliabilityMetric.PRESS_TO_AUDIO))
        val summary = series.summaries().single()
        assertEquals(30L, summary.p50)
        assertEquals(40L, summary.p95)
        assertEquals(1, summary.failureCount)
        assertTrue(summary.exceedsBudget)
    }

    @Test
    fun `failure outcomes leave the window with their samples`() {
        val series = DictationReliabilitySeries(capacity = 2)
        series.add(DictationReliabilityMetric.COMMIT, 500, success = false)
        series.add(DictationReliabilityMetric.COMMIT, 50, success = true)
        series.add(DictationReliabilityMetric.COMMIT, 60, success = true)

        val summary = series.summaries().single()
        assertEquals(0, summary.failureCount)
        assertFalse(summary.exceedsBudget)
    }

    @Test
    fun `persisted outcomes keep the bounded failure window`() {
        val series = DictationReliabilitySeries(capacity = 3)
        series.replace(
            DictationReliabilityMetric.COMMIT,
            values = listOf(20, 30, 40),
            failureCount = 1,
            outcomes = listOf(true, false, true),
        )

        assertEquals(listOf(true, false, true), series.outcomes(DictationReliabilityMetric.COMMIT))
        assertEquals(1, series.failureCount(DictationReliabilityMetric.COMMIT))
    }

    @Test
    fun `budget flag uses p95 and failure outcomes`() {
        val series = DictationReliabilitySeries(capacity = 20)
        repeat(19) { series.add(DictationReliabilityMetric.COMMIT, 50, success = true) }
        series.add(DictationReliabilityMetric.COMMIT, 500, success = true)

        val summary = series.summaries().single()
        assertFalse(summary.exceedsBudget)

        series.add(DictationReliabilityMetric.COMMIT, 60, success = false)
        assertTrue(series.summaries().single().exceedsBudget)
    }

    @Test
    fun `percentiles are deterministic for small samples`() {
        val values = listOf(10L, 20L, 30L, 40L, 50L)
        assertEquals(30L, percentile(values, 50))
        assertEquals(50L, percentile(values, 95))
        assertEquals(50L, percentile(values, 99))
    }
}
