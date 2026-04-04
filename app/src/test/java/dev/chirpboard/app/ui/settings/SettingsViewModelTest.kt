package dev.chirpboard.app.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var obsidianPreferences: ObsidianPreferences
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk()
        packageManager = mockk()
        obsidianPreferences = mockk()

        every { context.packageManager } returns packageManager
        every { context.packageName } returns "dev.chirpboard.app"

        val appInfo = ApplicationInfo().apply { flags = 0 }
        every { context.applicationInfo } returns appInfo

        val flow = MutableStateFlow<String?>(null)
        every { obsidianPreferences.globalVaultUri } returns flow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `handles package not found gracefully`() =
        runTest {
            every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()

            val viewModel = SettingsViewModel(context, obsidianPreferences)
            val state = viewModel.uiState.value

            assertEquals("Unknown", state.appVersion)
            assertEquals("Unknown", state.buildNumber)
        }
}
