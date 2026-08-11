package dev.chirpboard.app.shortcut

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.repository.ProfileRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Backs the profile list's "Add to Home screen" action. The Room profile read runs in
 * [viewModelScope] (not a composition scope), so a quick navigate-away no longer cancels the
 * pin request before the system dialog appears.
 */
@HiltViewModel
internal class ProfilePinShortcutViewModel
    @Inject
    constructor(
        private val profileRepository: ProfileRepository,
        private val profileShortcutManager: ProfileShortcutManager,
    ) : ViewModel() {
        fun isRequestPinShortcutSupported(): Boolean = profileShortcutManager.isRequestPinShortcutSupported()

        /**
         * Reads the full profile (the pin label needs its name) and hands the system the
         * pin-shortcut dialog. No-ops if the profile was deleted in the meantime.
         */
        fun requestPinShortcut(profileId: UUID) {
            viewModelScope.launch {
                profileRepository.getProfile(profileId)?.let(profileShortcutManager::requestPinShortcut)
            }
        }
    }
