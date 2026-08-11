package dev.chirpboard.app.feature.recording.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlowSkippingErrors
import dev.chirpboard.app.feature.recording.ui.launchRepositoryMutation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel
    @Inject
    constructor(
        private val profileRepository: ProfileRepository,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        /** null until the first successful load — the screen shows neither list nor empty state. */
        val profiles: StateFlow<List<Profile>?> =
            profileRepository
                .getAllProfiles()
                .unwrapRepositoryFlowSkippingErrors { _errorMessage.value = it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        fun clearError() {
            _errorMessage.value = null
        }

        fun deleteProfile(profile: Profile) {
            launchRepositoryMutation("ProfilesVM", { _errorMessage.value = it }) {
                profileRepository.delete(profile)
            }
        }
    }
