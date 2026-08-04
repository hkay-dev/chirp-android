package dev.chirpboard.app.core.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationReliabilityMetricsTest {
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
        assertEquals(30L, summary.p95)
        assertEquals(1, summary.failureCount)
        assertTrue(summary.exceedsBudget)
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
        assertEquals(40L, percentile(values, 95))
        assertEquals(40L, percentile(values, 99))
    }
}
