package dev.chirpboard.app.feature.keyboard.service

import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.keyboard.state.toKeyboardState
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

internal object KeyboardServiceStartup {
    fun configureAudioFocusInterrupts(
        tag: String,
        audioFocusManager: AudioFocusManager,
        currentState: () -> KeyboardState,
        onRecordingInterrupted: () -> Unit,
    ) {
        audioFocusManager.onFocusLost = {
            Log.w(tag, "Audio focus lost during recording")
            if (currentState() is KeyboardState.Recording) {
                onRecordingInterrupted()
            }
        }
    }

    fun registerPhoneCallInterrupts(
        telephonyManager: TelephonyManager?,
        mainExecutor: Executor,
        tag: String,
        currentState: () -> KeyboardState,
        onRecordingInterrupted: () -> Unit,
    ): PhoneCallHandler? {
        if (telephonyManager == null) {
            return null
        }

        return PhoneCallHandler(telephonyManager, mainExecutor).apply {
            onCallStateChanged = { isInCall ->
                if (isInCall && currentState() is KeyboardState.Recording) {
                    Log.w(tag, "Phone call detected, stopping recording")
                    onRecordingInterrupted()
                }
            }
            register()
        }
    }

    fun startRecomposer(
        recomposerScope: CoroutineScope,
        recomposer: Recomposer,
    ) {
        recomposerScope.launch(AndroidUiDispatcher.Main) {
            recomposer.runRecomposeAndApplyChanges()
        }
    }

    fun observePreferences(
        scope: CoroutineScope,
        keyboardPreferences: KeyboardPreferences,
        onLlmEnabledChanged: (Boolean) -> Unit,
        onMicrophoneGainChanged: (Float) -> Unit,
    ) {
        observePreferences(
            scope = scope,
            llmEnabledFlow = keyboardPreferences.llmEnabled,
            microphoneGainFlow = keyboardPreferences.microphoneGain,
            onLlmEnabledChanged = onLlmEnabledChanged,
            onMicrophoneGainChanged = onMicrophoneGainChanged,
        )
    }

    internal fun observePreferences(
        scope: CoroutineScope,
        llmEnabledFlow: Flow<Boolean>,
        microphoneGainFlow: Flow<Float>,
        onLlmEnabledChanged: (Boolean) -> Unit,
        onMicrophoneGainChanged: (Float) -> Unit,
    ) {
        scope.launch {
            llmEnabledFlow.collect { enabled ->
                onLlmEnabledChanged(enabled)
            }
        }

        scope.launch {
            microphoneGainFlow.collect { gain ->
                onMicrophoneGainChanged(gain)
            }
        }
    }

    fun observeProcessingMode(
        scope: CoroutineScope,
        modeRepository: ProcessingModeRepository,
        onModeChanged: (ProcessingMode) -> Unit,
    ) {
        observeProcessingMode(
            scope = scope,
            currentModeFlow = modeRepository.currentMode,
            onModeChanged = onModeChanged,
        )
    }

    internal fun observeProcessingMode(
        scope: CoroutineScope,
        currentModeFlow: Flow<ProcessingMode>,
        onModeChanged: (ProcessingMode) -> Unit,
    ) {
        scope.launch {
            currentModeFlow.collect { mode ->
                onModeChanged(mode)
            }
        }
    }

    fun observeRecordingState(
        scope: CoroutineScope,
        recordingStateManager: RecordingStateManager,
        currentState: () -> KeyboardState,
        onStateChanged: (KeyboardState) -> Unit,
        tag: String,
    ) {
        scope.launch {
            recordingStateManager.state.collect { recordingState ->
                val keyboardState = currentState()
                if (keyboardState.isKeyboardManagedState()) {
                    return@collect
                }

                val derivedState = recordingState.toKeyboardState() ?: return@collect
                if (derivedState != keyboardState) {
                    Log.d(tag, "Syncing state from RecordingStateManager: $recordingState -> $derivedState")
                    onStateChanged(derivedState)
                }
            }
        }
    }
}

private fun KeyboardState.isKeyboardManagedState(): Boolean {
    return this is KeyboardState.Transcribing ||
        this is KeyboardState.Polishing ||
        this is KeyboardState.Downloading ||
        this is KeyboardState.ModelNotReady ||
        this is KeyboardState.LlmError
}
