package dev.chirpboard.app.feature.llm.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ProcessingPromptEditorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val modeRepository: ProcessingModeRepository,
    ) : ViewModel() {
        private val presetId: String = savedStateHandle.get<String>(PRESET_ID_ARG).orEmpty()
        private var initialName: String = ""
        private var initialPrompt: String = ""

        data class UiState(
            val presetId: String = "",
            val name: String = "",
            val prompt: String = "",
            val isBuiltIn: Boolean = false,
            val canEditName: Boolean = false,
            val canEditPrompt: Boolean = true,
            val isModified: Boolean = false,
            val isNewPreset: Boolean = false,
            val saveEnabled: Boolean = false,
            val resetEnabled: Boolean = false,
            val deleteEnabled: Boolean = false,
            val statusMessage: String? = null,
        )

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                if (presetId == NEW_PRESET_ID) {
                    _uiState.value =
                        UiState(
                            presetId = NEW_PRESET_ID,
                            name = "",
                            prompt = "",
                            isNewPreset = true,
                            canEditName = true,
                            canEditPrompt = true,
                            saveEnabled = false,
                        )
                } else {
                    loadPreset(presetId)
                }
            }
        }

        fun updateName(name: String) {
            _uiState.update { current ->
                current.copy(
                    name = name,
                    saveEnabled = canSave(current.copy(name = name)),
                )
            }
        }

        fun updatePrompt(prompt: String) {
            _uiState.update { current ->
                current.copy(
                    prompt = prompt,
                    saveEnabled = canSave(current.copy(prompt = prompt)),
                )
            }
        }

        fun save(onSaved: () -> Unit) {
            viewModelScope.launch {
                val state = _uiState.value
                if (!state.saveEnabled) return@launch

                runCatching {
                    if (state.isNewPreset) {
                        modeRepository.addCustomPreset(
                                name = state.name,
                                prompt = state.prompt,
                            )
                    } else {
                        if (state.canEditName && !state.isBuiltIn) {
                            modeRepository.renameCustomPreset(state.presetId, state.name)
                        }
                        modeRepository.updatePresetPrompt(state.presetId, state.prompt)
                    }
                }.onSuccess {
                    onSaved()
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(statusMessage = error.message ?: "Unable to save preset")
                    }
                }
            }
        }

        fun resetToOriginal() {
            viewModelScope.launch {
                val state = _uiState.value
                if (state.isNewPreset || !state.resetEnabled) return@launch

                modeRepository.resetPresetPrompt(state.presetId)
                loadPreset(state.presetId)
                _uiState.update { it.copy(statusMessage = null) }
            }
        }

        fun delete(onDeleted: () -> Unit) {
            viewModelScope.launch {
                val state = _uiState.value
                if (!state.deleteEnabled) return@launch
                modeRepository.deleteCustomPreset(state.presetId)
                onDeleted()
            }
        }

        fun dismissStatusMessage() {
            _uiState.update { it.copy(statusMessage = null) }
        }

        private suspend fun loadPreset(id: String) {
            val preset =
                modeRepository.promptPresets.first().find { it.id == id }
                    ?: run {
                        _uiState.value =
                            UiState(
                                presetId = id,
                                statusMessage = "Preset not found",
                            )
                        return
                    }
            applyPreset(preset)
        }

        private fun applyPreset(preset: ProcessingPromptPreset) {
            initialName = preset.name
            initialPrompt = preset.prompt.orEmpty()
            _uiState.value =
                UiState(
                    presetId = preset.id,
                    name = preset.name,
                    prompt = initialPrompt,
                    isBuiltIn = preset.isBuiltIn,
                    canEditName = !preset.isBuiltIn,
                    canEditPrompt = preset.canEditPrompt,
                    isModified = preset.isModified,
                    saveEnabled = false,
                    resetEnabled = preset.isModified && preset.canEditPrompt,
                    deleteEnabled = !preset.isBuiltIn,
                )
        }

        private fun canSave(state: UiState): Boolean {
            val trimmedName = state.name.trim()
            val trimmedPrompt = state.prompt.trim()
            if (trimmedName.isEmpty() || trimmedPrompt.isEmpty()) return false
            if (state.isNewPreset) return true
            if (!state.canEditPrompt && trimmedName == initialName.trim()) return false
            return trimmedName != initialName.trim() || trimmedPrompt != initialPrompt.trim()
        }

        companion object {
            const val PRESET_ID_ARG = "presetId"
            const val NEW_PRESET_ID = "new"
        }
    }
