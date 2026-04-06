package dev.chirpboard.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: android.app.Application,
    private val obsidianPreferences: ObsidianPreferences
) : ViewModel() {

    data class UiState(
        val appVersion: String = "",
        val buildNumber: String = "",
        val isObsidianConnected: Boolean = false,
        val isDebugBuild: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadAppInfo()
        observeObsidianConnection()
    }

    private fun loadAppInfo() {
        try {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val appInfo = application.applicationInfo
            val isDebug = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            _uiState.update { state ->
                state.copy(
                    appVersion = packageInfo.versionName ?: "Unknown",
                    buildNumber = packageInfo.longVersionCode.toString(),
                    isDebugBuild = isDebug
                )
            }
        } catch (e: PackageManager.NameNotFoundException) {
            _uiState.update { state ->
                state.copy(
                    appVersion = "Unknown",
                    buildNumber = "Unknown"
                )
            }
        }
    }

    private fun observeObsidianConnection() {
        viewModelScope.launch {
            obsidianPreferences.globalVaultUri.collect { vaultUri ->
                _uiState.update { state ->
                    state.copy(isObsidianConnected = vaultUri != null)
                }
            }
        }
    }
}
