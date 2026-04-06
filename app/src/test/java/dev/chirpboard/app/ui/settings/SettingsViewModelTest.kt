package dev.chirpboard.app.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()
    private val obsidianPreferences = mockk<ObsidianPreferences>()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "dev.chirpboard.app"
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initializes with correct app info`() {
        val packageInfo = PackageInfo().apply {
            versionName = "1.0.0"
            longVersionCode = 100L
        }
        val appInfo = ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }

        every { packageManager.getPackageInfo("dev.chirpboard.app", 0) } returns packageInfo
        every { context.applicationInfo } returns appInfo

        val vaultUriFlow = MutableStateFlow<String?>("content://test")
        every { obsidianPreferences.globalVaultUri } returns vaultUriFlow

        val viewModel = SettingsViewModel(mockk(relaxed=true), obsidianPreferences)

        val state = viewModel.uiState.value
        assertEquals("1.0.0", state.appVersion)
        assertEquals("100", state.buildNumber)
        assertEquals(true, state.isDebugBuild)
        assertEquals(true, state.isObsidianConnected)
    }
}
