package dev.chirpboard.app.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val application = mockk<Application>()
    private val packageManager = mockk<PackageManager>()
    private val obsidianPreferences = mockk<ObsidianPreferences>()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { application.packageManager } returns packageManager
        every { application.packageName } returns "dev.chirpboard.app"
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initializes with correct app info`() {
        val packageInfo = mockk<PackageInfo>()
        packageInfo.versionName = "1.0.0"
        every { packageInfo.longVersionCode } returns 100L

        val appInfo = mockk<ApplicationInfo>()
        appInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        every { application.applicationInfo } returns appInfo
        every { packageManager.getPackageInfo("dev.chirpboard.app", 0) } returns packageInfo

        val vaultUriFlow = MutableStateFlow<String?>("content://test")
        every { obsidianPreferences.globalVaultUri } returns vaultUriFlow

        val viewModel = SettingsViewModel(application, obsidianPreferences)

        val state = viewModel.uiState.value
        assertEquals("1.0.0", state.appVersion)
        assertEquals("100", state.buildNumber)
        assertEquals(true, state.isDebugBuild)
        assertEquals(true, state.isObsidianConnected)
    }
}
