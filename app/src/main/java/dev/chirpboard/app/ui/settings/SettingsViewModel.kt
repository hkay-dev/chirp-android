package dev.chirpboard.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val application: android.app.Application,
        private val obsidianPreferences: ObsidianPreferences,
        private val dynamicColorPreference: DynamicColorPreference,
    ) : ViewModel() {
        data class UiState(
            val appVersion: String = "",
            val buildNumber: String = "",
            val isObsidianConnected: Boolean = false,
            val isDebugBuild: Boolean = false,
            val useDynamicColor: Boolean = DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR,
        )

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        init {
            loadAppInfo()
            observeObsidianConnection()
            observeDynamicColor()
        }

        private fun loadAppInfo() {
            try {
                val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
                val appInfo = application.applicationInfo
                val isDebug = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                _uiState.update { state ->
                    state.copy(
                        appVersion = packageInfo.versionName ?: application.getString(R.string.about_version_unknown),
                        buildNumber = PackageInfoCompat.getLongVersionCode(packageInfo).toString(),
                        isDebugBuild = isDebug,
                    )
                }
            } catch (e: PackageManager.NameNotFoundException) {
                _uiState.update { state ->
                    state.copy(
                        appVersion = application.getString(R.string.about_version_unknown),
                        buildNumber = application.getString(R.string.about_version_unknown),
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

        private fun observeDynamicColor() {
            viewModelScope.launch {
                dynamicColorPreference.useDynamicColor.collect { enabled ->
                    _uiState.update { state ->
                        state.copy(useDynamicColor = enabled)
                    }
                }
            }
        }

        /**
         * Persist the "Use system colors (Material You)" choice. The new value is reflected back
         * through [observeDynamicColor]; the app + keyboard recompose against the chosen palette.
         */
        fun setUseDynamicColor(enabled: Boolean) {
            viewModelScope.launch {
                dynamicColorPreference.setUseDynamicColor(enabled)
            }
        }
    }
