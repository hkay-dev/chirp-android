package dev.chirpboard.app.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

data class RepositoryFlowState<T>(
    val value: T,
    val errorMessage: String? = null,
)

private const val INITIAL_RETRY_DELAY_MS = 1_000L
private const val MAX_RETRY_DELAY_MS = 30_000L
private const val MAX_RETRY_DELAY_DOUBLINGS = 5

private fun retryDelayMs(attempt: Long): Long =
    (INITIAL_RETRY_DELAY_MS shl attempt.coerceAtMost(MAX_RETRY_DELAY_DOUBLINGS.toLong()).toInt())
        .coerceAtMost(MAX_RETRY_DELAY_MS)

// A Room flow that throws (transient SQLite error, converter failure) is DEAD: `catch` alone
// would emit the fallback and complete, leaving the screen stuck on the empty default until
// its ViewModel is recreated. Retrying with backoff re-subscribes the query so the UI heals
// itself once the underlying error clears.
private suspend fun <R> FlowCollector<R>.logAndScheduleRetry(
    tag: String,
    error: Throwable,
    attempt: Long,
    fallback: R,
): Boolean {
    if (error is CancellationException) {
        return false
    }
    Log.e(tag, "Repository flow failed; emitting safe default and retrying", error)
    emit(fallback)
    delay(retryDelayMs(attempt))
    return true
}

internal fun <T> Flow<T>.catchRepositoryFlow(
    tag: String,
    default: T,
): Flow<T> =
    retryWhen { error, attempt ->
        logAndScheduleRetry(tag, error, attempt, default)
    }.distinctUntilChanged()

// Room-generated Flows re-emit on every table invalidation even when the query result is
// byte-for-byte identical (e.g. a background status tick on an unrelated row). distinctUntilChanged
// here drops those identical re-emissions at the repository boundary so downstream collectors
// (Home list, Studio transcript rebuild) do not redo their work for no observable change.
internal fun <T> Flow<T>.catchRepositoryFlowState(
    tag: String,
    default: T,
): Flow<RepositoryFlowState<T>> =
    map { RepositoryFlowState(value = it) }
        .retryWhen { error, attempt ->
            logAndScheduleRetry(
                tag = tag,
                error = error,
                attempt = attempt,
                fallback =
                    RepositoryFlowState(
                        value = default,
                        errorMessage = error.message ?: "Data load failed",
                    ),
            )
        }.distinctUntilChanged()

fun <T> Flow<RepositoryFlowState<T>>.unwrapRepositoryFlow(
    onError: (String) -> Unit,
): Flow<T> =
    onEach { state ->
        state.errorMessage?.let(onError)
    }.map { it.value }
