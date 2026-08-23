package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import dev.chirpboard.app.feature.llm.model.ProcessingModeListItem
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ProcessingPromptSettingsViewModel
    @Inject
    constructor(
        // I18N-08: prompt-settings status copy comes from resources.
        @ApplicationContext private val appContext: Context,
        private val modeRepository: ProcessingModeRepository,
    ) : ViewModel() {
        data class UiState(
            val presets: List<ProcessingPromptPreset> = emptyList(),
            val selectableModes: List<ProcessingModeListItem> = emptyList(),
            val defaultModeId: String = ProcessingModeDefaults.DEFAULT_MODE_ID,
            val statusMessage: String? = null,
        )

        private val statusMessage = MutableStateFlow<String?>(null)

        val uiState: StateFlow<UiState> =
            combine(
                modeRepository.promptPresets,
                modeRepository.selectableModes,
                modeRepository.defaultModeId,
                statusMessage,
            ) { presets, selectableModes, defaultModeId, status ->
                UiState(
                    presets = presets,
                    selectableModes = selectableModes,
                    defaultModeId = defaultModeId,
                    statusMessage = status,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        fun setDefaultMode(modeId: String) {
            viewModelScope.launch {
                // A failed write used to be invisible: the dropdown snapped back with no
                // explanation because nothing surfaced the DataStore error.
                runCatching {
                    modeRepository.setModeById(modeId)
                }.onSuccess {
                    statusMessage.update { null }
                }.onFailure { error ->
                    // I18N-05: raw exception text stays in logs, not the status line.
                    Log.e(TAG, "Failed to set default processing mode", error)
                    statusMessage.value = appContext.getString(R.string.llm_prompt_default_mode_failed)
                }
            }
        }

        fun deleteCustomPreset(presetId: String) {
            viewModelScope.launch {
                runCatching {
                    modeRepository.deleteCustomPreset(presetId)
                }.onSuccess {
                    statusMessage.update { null }
                }.onFailure { error ->
                    // I18N-05: raw exception text stays in logs, not the status line.
                    Log.e(TAG, "Failed to delete preset", error)
                    statusMessage.value = appContext.getString(R.string.llm_prompt_delete_failed)
                }
            }
        }

        fun dismissStatusMessage() {
            statusMessage.value = null
        }

        companion object {
            private const val TAG = "PromptSettingsVM"
        }
    }
