package dev.chirpboard.app.feature.recording.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a repository write in [ViewModel.viewModelScope], reporting failure instead of crashing.
 *
 * Repository suspend functions pass DAO exceptions straight through, so a bare
 * `viewModelScope.launch { repository.delete(...) }` turns any transient SQLite failure into an
 * app crash via the unhandled-exception path. The read side already degrades gracefully
 * (`unwrapRepositoryFlow` routes errors into the screen's snackbar); this gives writes the same
 * behavior. The message mirrors the read-side convention: the exception message when present,
 * otherwise a generic fallback.
 */
internal fun ViewModel.launchRepositoryMutation(
    tag: String,
    onError: (String) -> Unit,
    block: suspend () -> Unit,
): Job =
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Repository mutation failed", e)
            onError(e.message ?: "Operation failed")
        }
    }
