package dev.parakeeboard.app.feature.recording.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.parakeeboard.app.data.entity.Profile
import dev.parakeeboard.app.data.repository.ProfileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository
) : ViewModel() {
    
    val profiles: StateFlow<List<Profile>> = profileRepository.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            profileRepository.delete(profile)
        }
    }
}
