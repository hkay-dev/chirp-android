package dev.chirpboard.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val appVersion: String = "",
        val buildNumber: String = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadAppInfo()
    }

    private fun loadAppInfo() {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            _uiState.value = UiState(
                appVersion = packageInfo.versionName ?: "Unknown",
                buildNumber = packageInfo.longVersionCode.toString()
            )
        } catch (e: PackageManager.NameNotFoundException) {
            _uiState.value = UiState(
                appVersion = "Unknown",
                buildNumber = "Unknown"
            )
        }
    }
}
