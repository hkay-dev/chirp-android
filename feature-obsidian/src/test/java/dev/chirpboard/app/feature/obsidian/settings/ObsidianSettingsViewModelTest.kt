package dev.chirpboard.app.feature.obsidian.settings

import android.net.Uri
import app.cash.turbine.test
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)


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

            coEvery { obsidianManager.hasVaultAccess(any()) } returns true
            coEvery { obsidianManager.getVaultDisplayName(any()) } returns "My Vault"

            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(uriStr, state.vaultUri)
                assertEquals("My Vault", state.vaultName)
                assertTrue(state.autoExportEnabled)
                assertTrue(state.hasAccess)
                assertFalse(state.isLoading)
            }
        }

    @Test
    fun `setVaultUri persists the grant and then the preference`() =
        runTest {
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            val testUri = mockk<Uri>(relaxed = true)
            every { testUri.toString() } returns "content://test"
            coEvery { obsidianManager.takeVaultPermission(testUri) } returns true

            viewModel.setVaultUri(testUri)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerifyOrder {
                obsidianManager.takeVaultPermission(testUri)
                preferences.setGlobalVaultUri("content://test")
            }
        }

    @Test
    fun `setVaultUri does not store a vault whose grant could not be persisted`() =
        runTest {
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            val testUri = mockk<Uri>(relaxed = true)
            every { testUri.toString() } returns "content://test"
            coEvery { obsidianManager.takeVaultPermission(testUri) } returns false

            viewModel.setVaultUri(testUri)
            testDispatcher.scheduler.advanceUntilIdle()

            // A vault we can't reopen after a restart must never be saved as configured,
            // and the failure must be visible to the user.
            coVerify(exactly = 0) { preferences.setGlobalVaultUri(any()) }
            assertTrue(viewModel.uiState.value.vaultSelectionFailed)
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
    fun `clearVault releases the persisted grant for the configured vault`() =
        runTest {
            globalVaultUriFlow.value = "content://test"
            coEvery { obsidianManager.hasVaultAccess(any()) } returns true
            coEvery { obsidianManager.getVaultDisplayName(any()) } returns "My Vault"
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.clearVault()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { obsidianManager.releaseVaultPermission(any()) }
            coVerify { preferences.setGlobalVaultUri(null) }
        }

    @Test
    fun `setVaultUri releases the previous vault grant only after the new one is stored`() =
        runTest {
            globalVaultUriFlow.value = "content://old"
            coEvery { obsidianManager.hasVaultAccess(any()) } returns true
            coEvery { obsidianManager.getVaultDisplayName(any()) } returns "Old Vault"
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            val newUri = mockk<Uri>(relaxed = true)
            every { newUri.toString() } returns "content://new"
            coEvery { obsidianManager.takeVaultPermission(newUri) } returns true
            viewModel.setVaultUri(newUri)
            testDispatcher.scheduler.advanceUntilIdle()

            // Releasing before persisting would leave the app with no working vault if the
            // process died in between.
            coVerifyOrder {
                preferences.setGlobalVaultUri("content://new")
                obsidianManager.releaseVaultPermission(any())
            }
        }

    @Test
    fun `setAutoExport writes the requested value`() =
        runTest {
            autoExportEnabledFlow.value = true
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.setAutoExport(false)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.setAutoExportEnabled(false) }
        }

    @Test
    fun `setAutoExport double tap ends on the last requested value`() =
        runTest {
            autoExportEnabledFlow.value = false
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            // Off -> on -> off, both taps landing before either write completes.
            viewModel.setAutoExport(true)
            viewModel.setAutoExport(false)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.setAutoExportEnabled(true) }
            coVerify { preferences.setAutoExportEnabled(false) }
        }

    @Test
    fun `refreshAccessStatus checks access again`() =
        runTest {
            globalVaultUriFlow.value = "content://test"
            val viewModel = ObsidianSettingsViewModel(preferences, obsidianManager)
            testDispatcher.scheduler.advanceUntilIdle()

            coEvery { obsidianManager.hasVaultAccess(any()) } returns false

            viewModel.refreshAccessStatus()
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasAccess)
        }
}
