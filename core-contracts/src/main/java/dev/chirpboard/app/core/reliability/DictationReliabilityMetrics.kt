package dev.chirpboard.app.core.reliability

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DictationReliabilityMetric(
    val displayName: String,
    val budget: Long,
) {
    PRESS_TO_AUDIO("Press to durable audio", 250),
    STOP_TO_RAW("Stop to raw text", 2_500),
    STREAMING_FIRST_TEXT("Press to preview text", 1_500),
    AI_PROCESSING("AI processing", 10_000),
    COMMIT("Raw text to commit", 150),
    CAPTURE_GAP("Capture gap", 20),
    RECORDER_RESTARTS("Recorder restarts", 0),
}

data class DictationMetricSummary(
    val metric: DictationReliabilityMetric,
    val count: Int,
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val maximum: Long,
    val failureCount: Int,
) {
    val exceedsBudget: Boolean
        get() = p95 > metric.budget || failureCount > 0
}

data class ImeReliabilitySoakState(
    val active: Boolean = false,
    val targetSessions: Int = 0,
    val completedSessions: Int = 0,
    val failedSessions: Int = 0,
    val refuseNextCommit: Boolean = false,
)

data class DictationReliabilitySnapshot(
    val summaries: List<DictationMetricSummary> = emptyList(),
    val soak: ImeReliabilitySoakState = ImeReliabilitySoakState(),
)

/**
 * Content-free, bounded, device-local IME reliability telemetry. Only elapsed milliseconds,
 * counts, and pass/fail bits are stored. No transcript, audio, package, field, or model prompt
 * data enters this store.
 */
object DictationReliabilityMetrics {
    private const val PREFS = "dictation_reliability_metrics"
    private const val MAX_SAMPLES = 200
    private val lock = Any()
    private var context: Context? = null
    private val series = DictationReliabilitySeries(MAX_SAMPLES)
    private var soak = ImeReliabilitySoakState()
    private val _snapshot = MutableStateFlow(DictationReliabilitySnapshot())
    val snapshot: StateFlow<DictationReliabilitySnapshot> = _snapshot.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            this.context = context.applicationContext
            runCatching {
                val prefs = this.context!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                DictationReliabilityMetric.entries.forEach { metric ->
                    val values =
                        prefs.getString("values_${metric.name}", null)
                            ?.split(',')
                            ?.mapNotNull(String::toLongOrNull)
                            .orEmpty()
                    val failures = prefs.getInt("failures_${metric.name}", 0)
                    series.replace(metric, values, failures)
                }
                soak =
                    ImeReliabilitySoakState(
                        active = prefs.getBoolean("soak_active", false),
                        targetSessions = prefs.getInt("soak_target", 0),
                        completedSessions = prefs.getInt("soak_completed", 0),
                        failedSessions = prefs.getInt("soak_failed", 0),
                        refuseNextCommit = prefs.getBoolean("refuse_next_commit", false),
                    )
            }
            publish()
        }
    }

    fun record(
        metric: DictationReliabilityMetric,
        value: Long,
        success: Boolean = true,
    ) {
        synchronized(lock) {
            series.add(metric, value.coerceAtLeast(0L), success)
            persistMetric(metric)
            publish()
        }
    }

    fun startSoak(targetSessions: Int = 25) {
        synchronized(lock) {
            soak = ImeReliabilitySoakState(active = true, targetSessions = targetSessions.coerceAtLeast(1))
            persistSoak()
            publish()
        }
    }

    fun stopSoak() {
        synchronized(lock) {
            soak = soak.copy(active = false)
            persistSoak()
            publish()
        }
    }

    fun completeSoakSession(success: Boolean) {
        synchronized(lock) {
            if (!soak.active) return
            val completed = soak.completedSessions + 1
            soak =
                soak.copy(
                    active = completed < soak.targetSessions,
                    completedSessions = completed,
                    failedSessions = soak.failedSessions + if (success) 0 else 1,
                )
            persistSoak()
            publish()
        }
    }

    fun armCommitRefusal() {
        synchronized(lock) {
            soak = soak.copy(refuseNextCommit = true)
            persistSoak()
            publish()
        }
    }

    fun consumeCommitRefusal(): Boolean =
        synchronized(lock) {
            if (!soak.refuseNextCommit) return@synchronized false
            soak = soak.copy(refuseNextCommit = false)
            persistSoak()
            publish()
            true
        }

    fun clear() {
        synchronized(lock) {
            series.clear()
            soak = ImeReliabilitySoakState()
            runCatching { context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply() }
            publish()
        }
    }

    private fun persistMetric(metric: DictationReliabilityMetric) {
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val samples = series.values(metric).joinToString(",")
        prefs.edit()
            .putString("values_${metric.name}", samples)
            .putInt("failures_${metric.name}", series.failureCount(metric))
            .apply()
    }

    private fun persistSoak() {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("soak_active", soak.active)
            ?.putInt("soak_target", soak.targetSessions)
            ?.putInt("soak_completed", soak.completedSessions)
            ?.putInt("soak_failed", soak.failedSessions)
            ?.putBoolean("refuse_next_commit", soak.refuseNextCommit)
            ?.apply()
    }

    private fun publish() {
        _snapshot.value = DictationReliabilitySnapshot(series.summaries(), soak)
    }
}

internal class DictationReliabilitySeries(
    private val capacity: Int,
) {
    private val samples = mutableMapOf<DictationReliabilityMetric, MutableList<Long>>()
    private val failures = mutableMapOf<DictationReliabilityMetric, Int>()

    fun add(metric: DictationReliabilityMetric, value: Long, success: Boolean) {
        val values = samples.getOrPut(metric, ::mutableListOf)
        values += value
        while (values.size > capacity) values.removeAt(0)
        if (!success) failures[metric] = failureCount(metric) + 1
    }

    fun replace(metric: DictationReliabilityMetric, values: List<Long>, failureCount: Int) {
        samples[metric] = values.takeLast(capacity).toMutableList()
        failures[metric] = failureCount.coerceAtLeast(0)
    }

    fun values(metric: DictationReliabilityMetric): List<Long> = samples[metric].orEmpty()

    fun failureCount(metric: DictationReliabilityMetric): Int = failures[metric] ?: 0

    fun summaries(): List<DictationMetricSummary> =
        DictationReliabilityMetric.entries.mapNotNull { metric ->
            val values = values(metric).sorted()
            if (values.isEmpty()) return@mapNotNull null
            DictationMetricSummary(
                metric = metric,
                count = values.size,
                p50 = percentile(values, 50),
                p95 = percentile(values, 95),
                p99 = percentile(values, 99),
                maximum = values.last(),
                failureCount = failureCount(metric),
            )
        }

    fun clear() {
        samples.clear()
        failures.clear()
    }
}

internal fun percentile(sortedValues: List<Long>, percentile: Int): Long {
    require(percentile in 0..100)
    if (sortedValues.isEmpty()) return 0L
    val index = ((percentile / 100.0) * (sortedValues.size - 1)).toInt()
    return sortedValues[index]
}
