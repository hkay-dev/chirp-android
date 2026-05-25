package dev.chirpboard.app.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

data class RepositoryFlowState<T>(
    val value: T,
    val errorMessage: String? = null,
)

internal fun <T> Flow<T>.catchRepositoryFlow(
    tag: String,
    default: T,
): Flow<T> =
    catch {
        if (it is CancellationException) {
            throw it
        }
        Log.e(tag, "Repository flow failed; emitting safe default", it)
        emit(default)
    }

internal fun <T> Flow<T>.catchRepositoryFlowState(
    tag: String,
    default: T,
): Flow<RepositoryFlowState<T>> =
    map { RepositoryFlowState(value = it) }.catch { error ->
        if (error is CancellationException) {
            throw error
        }
        Log.e(tag, "Repository flow failed; emitting safe default", error)
        emit(
            RepositoryFlowState(
                value = default,
                errorMessage = error.message ?: "Data load failed",
            ),
        )
    }

fun <T> Flow<RepositoryFlowState<T>>.unwrapRepositoryFlow(
    onError: (String) -> Unit,
): Flow<T> =
    onEach { state ->
        state.errorMessage?.let(onError)
    }.map { it.value }
