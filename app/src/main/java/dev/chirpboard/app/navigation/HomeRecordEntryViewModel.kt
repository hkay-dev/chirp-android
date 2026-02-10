package dev.chirpboard.app.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.download.ModelReadinessGate
import dev.chirpboard.app.download.ModelReadinessState
import dev.chirpboard.app.download.ModelReadinessUnavailableReason
import dev.chirpboard.app.download.ModelReadyResult
import dev.chirpboard.app.download.VerificationTrigger
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HomeRecordEntryViewModel @Inject constructor(
    private val modelReadinessGate: ModelReadinessGate
) : ViewModel() {

    val readinessState: StateFlow<ModelReadinessState> = modelReadinessGate.state

    private val _events = MutableSharedFlow<HomeRecordEntryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeRecordEntryEvent> = _events.asSharedFlow()

    fun warmupOnHomeVisible() {
        modelReadinessGate.warmupIfNeeded(VerificationTrigger.HOME_VISIBLE)
    }

    fun onRecordTapped() {
        if (readinessState.value is ModelReadinessState.Checking) {
            return
        }

        val tapStartedNs = System.nanoTime()
        Log.d(TAG, "record_tap_received")

        viewModelScope.launch {
            when (val result = modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP)) {
                is ModelReadyResult.Ready -> {
                    val elapsedMs = (System.nanoTime() - tapStartedNs) / 1_000_000
                    Log.d(TAG, "record_navigation_allowed in ${elapsedMs}ms (source=${result.source})")
                    _events.emit(HomeRecordEntryEvent.NavigateToRecord)
                }

                is ModelReadyResult.Unavailable -> {
                    val elapsedMs = (System.nanoTime() - tapStartedNs) / 1_000_000
                    Log.w(TAG, "record_navigation_blocked in ${elapsedMs}ms (reason=${result.reason})")
                    _events.emit(HomeRecordEntryEvent.ShowModelRequired(result.reason))
                }

                is ModelReadyResult.Error -> {
                    val elapsedMs = (System.nanoTime() - tapStartedNs) / 1_000_000
                    Log.e(TAG, "record_navigation_error in ${elapsedMs}ms")
                    _events.emit(HomeRecordEntryEvent.ShowError(result.message))
                }
            }
        }
    }

    companion object {
        private const val TAG = "HomeRecordEntryVM"
    }
}

sealed interface HomeRecordEntryEvent {
    data object NavigateToRecord : HomeRecordEntryEvent

    data class ShowModelRequired(
        val reason: ModelReadinessUnavailableReason
    ) : HomeRecordEntryEvent

    data class ShowError(
        val message: String
    ) : HomeRecordEntryEvent
}
