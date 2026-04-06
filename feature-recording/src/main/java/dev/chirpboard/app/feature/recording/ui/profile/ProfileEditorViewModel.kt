package dev.chirpboard.app.feature.recording.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.runtime.Stable
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProfileEditorViewModel
    @Inject
    constructor(
        private val profileRepository: ProfileRepository,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val profileId: UUID? =
            savedStateHandle
                .get<String>("profileId")
                ?.let { UUID.fromString(it) }

        val isEditing = profileId != null

@Stable
        data class UiState(
            val name: String = "",
            val icon: String = "",
            val autoTranscribe: Boolean = true,
            val autoTitle: Boolean = false,
            val autoSummary: Boolean = false,
            val autoExportToObsidian: Boolean = false,
            val obsidianVaultPath: String = "",
            val defaultProcessingMode: String? = null,
            val isLoading: Boolean = false,
            val isSaved: Boolean = false,
            val error: String? = null,
        )
        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        init {
            if (profileId != null) {
                loadProfile(profileId)
            } else {
                _uiState.update {
                    it.copy(
                        name = savedStateHandle.get<String>("name") ?: "",
                        icon = savedStateHandle.get<String>("icon") ?: "",
                        autoTranscribe = savedStateHandle.get<Boolean>("autoTranscribe") ?: true,
                        autoTitle = savedStateHandle.get<Boolean>("autoTitle") ?: false,
                        autoSummary = savedStateHandle.get<Boolean>("autoSummary") ?: false,
                        autoExportToObsidian = savedStateHandle.get<Boolean>("autoExportToObsidian") ?: false,
                        obsidianVaultPath = savedStateHandle.get<String>("obsidianVaultPath") ?: "",
                        defaultProcessingMode = savedStateHandle.get<String>("defaultProcessingMode"),
                    )
                }
            }
        }

        private fun loadProfile(id: UUID) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val profile = profileRepository.getProfile(id)
                if (profile != null) {
                    _uiState.update {
                        val savedName = savedStateHandle.get<String>("name")
                        val savedIcon = savedStateHandle.get<String>("icon")
                        val savedAutoTranscribe = savedStateHandle.get<Boolean>("autoTranscribe")
                        val savedAutoTitle = savedStateHandle.get<Boolean>("autoTitle")
                        val savedAutoSummary = savedStateHandle.get<Boolean>("autoSummary")
                        val savedAutoExportToObsidian = savedStateHandle.get<Boolean>("autoExportToObsidian")
                        val savedObsidianVaultPath = savedStateHandle.get<String>("obsidianVaultPath")
                        val savedDefaultProcessingMode = savedStateHandle.get<String>("defaultProcessingMode")
                        it.copy(
                            name = savedName ?: profile.name,
                            icon = savedIcon ?: profile.icon ?: "",
                            autoTranscribe = savedAutoTranscribe ?: profile.autoTranscribe,
                            autoTitle = savedAutoTitle ?: profile.autoTitle,
                            autoSummary = savedAutoSummary ?: profile.autoSummary,
                            autoExportToObsidian = savedAutoExportToObsidian ?: profile.autoExportToObsidian,
                            obsidianVaultPath = savedObsidianVaultPath ?: profile.obsidianVaultPath ?: "",
                            defaultProcessingMode = savedDefaultProcessingMode ?: profile.defaultProcessingMode,
                            isLoading = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Profile not found") }
                }
            }
        }

        fun updateName(name: String) {
            savedStateHandle["name"] = name
            _uiState.update { it.copy(name = name) }
        }

        fun updateIcon(icon: String) {
            savedStateHandle["icon"] = icon
            _uiState.update { it.copy(icon = icon) }
        }

        fun updateAutoTranscribe(enabled: Boolean) {
            savedStateHandle["autoTranscribe"] = enabled
            _uiState.update { it.copy(autoTranscribe = enabled) }
        }

        fun updateAutoTitle(enabled: Boolean) {
            savedStateHandle["autoTitle"] = enabled
            _uiState.update { it.copy(autoTitle = enabled) }
        }

        fun updateAutoSummary(enabled: Boolean) {
            savedStateHandle["autoSummary"] = enabled
            _uiState.update { it.copy(autoSummary = enabled) }
        }

        fun updateAutoExportToObsidian(enabled: Boolean) {
            savedStateHandle["autoExportToObsidian"] = enabled
            _uiState.update { it.copy(autoExportToObsidian = enabled) }
        }

        fun updateObsidianVaultPath(path: String) {
            savedStateHandle["obsidianVaultPath"] = path
            _uiState.update { it.copy(obsidianVaultPath = path) }
        }

        fun updateDefaultProcessingMode(mode: String?) {
            savedStateHandle["defaultProcessingMode"] = mode
            _uiState.update { it.copy(defaultProcessingMode = mode) }
        }

        fun save() {
            val state = _uiState.value

            if (state.name.isBlank()) {
                _uiState.update { it.copy(error = "Name is required") }
                return
            }

            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }

                try {
                    if (profileId != null) {
                        // Update existing profile
                        val existing = profileRepository.getProfile(profileId)
                        if (existing != null) {
                            val updated =
                                existing.copy(
                                    name = state.name.trim(),
                                    icon = state.icon.ifBlank { null },
                                    autoTranscribe = state.autoTranscribe,
                                    autoTitle = state.autoTitle,
                                    autoSummary = state.autoSummary,
                                    autoExportToObsidian = state.autoExportToObsidian,
                                    obsidianVaultPath = state.obsidianVaultPath.ifBlank { null },
                                    defaultProcessingMode = state.defaultProcessingMode,
                                )
                            profileRepository.update(updated)
                        }
                    } else {
                        // Create new profile
                        profileRepository.createProfile(
                            ProfileRepository.CreateProfileRequest(
                                name = state.name.trim(),
                                icon = state.icon.ifBlank { null },
                                autoTranscribe = state.autoTranscribe,
                                autoTitle = state.autoTitle,
                                autoSummary = state.autoSummary,
                                obsidianVaultPath = state.obsidianVaultPath.ifBlank { null },
                                autoExportToObsidian = state.autoExportToObsidian,
                                defaultProcessingMode = state.defaultProcessingMode,
                            ),
                        )
                    }

                    _uiState.update { it.copy(isLoading = false, isSaved = true) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to save") }
                }
            }
        }

        fun clearError() {
            _uiState.update { it.copy(error = null) }
        }
    }
