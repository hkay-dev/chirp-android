package dev.chirpboard.app.feature.obsidian.settings

import android.net.Uri
import app.cash.turbine.test
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObsidianSettingsViewModelTest {
    private val preferences = mockk<ObsidianPreferences>(relaxed = true)
    private val obsidianManager = mockk<ObsidianManager>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private val globalVaultUriFlow = MutableStateFlow<String?>(null)
    private val autoExportEnabledFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock Uri.parse
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val str = firstArg<String>()
            mockk<Uri> {
                every { toString() } returns str
            }
        }

        every { preferences.globalVaultUri } returns globalVaultUriFlow
        every { preferences.autoExportEnabled } returns autoExportEnabledFlow
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state emits correctly based on preferences`() =
        runTest {
            val uriStr = "content://my.uri"
            globalVaultUriFlow.value = uriStr
            autoExportEnabledFlow.value = true

            every { obsidianManager.hasVaultAccess(any()) } returns true
            every { obsidianManager.getVaultDisplayName(any()) } returns "My Vault"

            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)

            viewModel.uiState.test {
                // First emission is usually the default initial state
                val initialState = awaitItem()
                assertTrue(initialState.isLoading)

                // Next emission is the combined flow result
                val updatedState = awaitItem()
                assertEquals(uriStr, updatedState.vaultUri)
                assertEquals("My Vault", updatedState.vaultName)
                assertTrue(updatedState.autoExportEnabled)
                assertTrue(updatedState.hasAccess)
                assertFalse(updatedState.isLoading)
            }
        }

    @Test
    fun `setVaultUri updates preference`() =
        runTest {
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            val testUri =
                mockk<Uri> {
                    every { toString() } returns "content://test"
                }

            viewModel.setVaultUri(testUri)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.setGlobalVaultUri("content://test") }
        }

    @Test
    fun `clearVault clears uri and disables auto export`() =
        runTest {
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)

            viewModel.clearVault()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.setGlobalVaultUri(null) }
            coVerify { preferences.setAutoExportEnabled(false) }
        }

    @Test
    fun `toggleAutoExport flips current setting`() =
        runTest {
            autoExportEnabledFlow.value = true
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.toggleAutoExport()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.setAutoExportEnabled(false) }
        }

    @Test
    fun `refreshAccessStatus checks access again`() =
        runTest {
            globalVaultUriFlow.value = "content://test"
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            every { obsidianManager.hasVaultAccess(any()) } returns false

            viewModel.refreshAccessStatus()
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasAccess)
        }
}
