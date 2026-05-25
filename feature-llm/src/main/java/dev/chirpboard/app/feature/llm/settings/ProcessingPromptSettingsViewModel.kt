package dev.chirpboard.app.feature.llm.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import dev.chirpboard.app.feature.llm.model.ProcessingModeListItem
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ProcessingPromptSettingsViewModel
    @Inject
    constructor(
        private val modeRepository: ProcessingModeRepository,
    ) : ViewModel() {
        data class UiState(
            val presets: List<ProcessingPromptPreset> = emptyList(),
            val selectableModes: List<ProcessingModeListItem> = emptyList(),
            val defaultModeId: String = ProcessingModeDefaults.DEFAULT_MODE_ID,
        )

        val uiState: StateFlow<UiState> =
            combine(
                modeRepository.promptPresets,
                modeRepository.selectableModes,
                modeRepository.defaultModeId,
            ) { presets, selectableModes, defaultModeId ->
                UiState(
                    presets = presets,
                    selectableModes = selectableModes,
                    defaultModeId = defaultModeId,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        fun setDefaultMode(modeId: String) {
            viewModelScope.launch {
                modeRepository.setDefaultModeId(modeId)
            }
        }

        fun deleteCustomPreset(presetId: String) {
            viewModelScope.launch {
                modeRepository.deleteCustomPreset(presetId)
            }
        }
    }
