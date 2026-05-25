package dev.chirpboard.app.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadyResult
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeRecordEntryViewModel
    @Inject
    constructor(
        private val modelReadinessGate: SpeechModelReadinessGate,
    ) : ViewModel() {
        val readinessState: StateFlow<ModelReadinessState> = modelReadinessGate.state

        private val _events = Channel<HomeRecordEntryEvent>(Channel.BUFFERED)
        val events: Flow<HomeRecordEntryEvent> = _events.receiveAsFlow()

        fun warmupOnHomeVisible() {
            modelReadinessGate.warmupIfNeeded(VerificationTrigger.HOME_VISIBLE)
        }

        fun onRecordTapped(profileId: UUID? = null) {
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
                        _events.send(HomeRecordEntryEvent.NavigateToRecord(profileId))
                    }

                    is ModelReadyResult.Unavailable -> {
                        val elapsedMs = (System.nanoTime() - tapStartedNs) / 1_000_000
                        Log.w(TAG, "record_navigation_blocked in ${elapsedMs}ms (reason=${result.reason})")
                        _events.send(HomeRecordEntryEvent.ShowModelRequired(result.reason))
                    }

                    is ModelReadyResult.Error -> {
                        val elapsedMs = (System.nanoTime() - tapStartedNs) / 1_000_000
                        Log.e(TAG, "record_navigation_error in ${elapsedMs}ms")
                        _events.send(HomeRecordEntryEvent.ShowError(result.message))
                    }
                }
            }
        }

        companion object {
            private const val TAG = "HomeRecordEntryVM"
        }
    }

sealed interface HomeRecordEntryEvent {
    data class NavigateToRecord(
        val profileId: UUID? = null,
    ) : HomeRecordEntryEvent

    data class ShowModelRequired(
        val reason: ModelReadinessUnavailableReason,
    ) : HomeRecordEntryEvent

    data class ShowError(
        val message: String,
    ) : HomeRecordEntryEvent
}
