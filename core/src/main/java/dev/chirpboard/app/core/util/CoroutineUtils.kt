package dev.chirpboard.app.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Sealed class representing a UI state with loading, success, and error states.
 */
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()

    data class Success<T>(
        val data: T,
    ) : UiState<T>()

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : UiState<Nothing>()

    val isLoading: Boolean get() = this is Loading
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): String? = (this as? Error)?.message
}

/**
 * Map a Flow to UiState, handling errors.
 */
fun <T> Flow<T>.asUiState(): Flow<UiState<T>> =
    this
        .map<T, UiState<T>> { UiState.Success(it) }
        .catch {
            if (it is CancellationException) throw it
            emit(UiState.Error(it.message ?: "Unknown error", it))
        }

/**
 * Execute a suspend function and wrap result in UiState.
 */
suspend fun <T> runCatchingUiState(block: suspend () -> T): UiState<T> =
    try {
        UiState.Success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        UiState.Error(e.message ?: "Unknown error", e)
    }

/**
 * Launch a coroutine that updates a UiState based on result.
 */
fun <T> CoroutineScope.launchWithUiState(
    onStateChange: (UiState<T>) -> Unit,
    block: suspend () -> T,
) {
    launch {
        onStateChange(UiState.Loading)
        onStateChange(runCatchingUiState(block))
    }
}
