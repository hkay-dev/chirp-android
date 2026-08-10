package dev.chirpboard.app.ui.settings

import android.content.Context
import android.net.Uri
import dev.chirpboard.app.backup.BackupSection
import dev.chirpboard.app.backup.ChirpBackupManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The export handoff across the SAF picker: the picker result must export exactly what the
 * user launched the picker for, and a result arriving with no pending request (process death
 * rebuilt the ViewModel) must abort instead of silently exporting the default selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var appContext: Context
    private lateinit var backupManager: ChirpBackupManager
    private val uri = mockk<Uri>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        appContext = mockk()
        every { appContext.getString(any()) } returns "message"
        every { appContext.resources } returns
            mockk {
                every { getQuantityString(any(), any(), *anyVararg()) } returns "saved"
            }
        backupManager = mockk()
        coEvery { backupManager.sectionCounts() } returns
            ChirpBackupManager.SectionCounts(
                settings = 1,
                tags = 2,
                profiles = 0,
                wordReplacements = 0,
                processingPresets = 0,
                apiKeys = 0,
                isSecureStorageAvailable = true,
            )
        coEvery { backupManager.suggestedBackupFileName() } returns "chirp.chirp-backup"
        coEvery { backupManager.discardBackupFile(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BackupRestoreViewModel(appContext, backupManager)

    @Test
    fun `picker result exports the selection captured when the picker was launched`() =
        runTest {
            coEvery { backupManager.exportToUri(any(), any(), any()) } returns Result.success(2)
            val vm = viewModel()
            vm.startExport()
            // The user can still reach the checkboxes while the picker animates in; a toggle
            // after launch must not change what this export writes.
            vm.toggleExportSection(BackupSection.TAGS)

            vm.onExportFileChosen(uri)

            coVerify(exactly = 1) {
                backupManager.exportToUri(uri, setOf(BackupSection.SETTINGS, BackupSection.TAGS), null)
            }
        }

    @Test
    fun `picker result without a pending export aborts and discards the created file`() =
        runTest {
            val vm = viewModel()

            // No startExport(): the process died behind the picker and the ViewModel was rebuilt.
            vm.onExportFileChosen(uri)

            coVerify(exactly = 1) { backupManager.discardBackupFile(uri) }
            coVerify(exactly = 0) { backupManager.exportToUri(any(), any(), any()) }
            assertTrue(vm.uiState.value.exportMessage is BackupRestoreViewModel.StatusMessage.Error)
        }

    @Test
    fun `cancelled picker clears the pending export without exporting`() =
        runTest {
            val vm = viewModel()
            vm.startExport()

            vm.onExportFileChosen(null)
            vm.onExportFileChosen(uri)

            // The second result has no pending export left, so it must abort, not export.
            coVerify(exactly = 0) { backupManager.exportToUri(any(), any(), any()) }
            assertEquals(2, vm.uiState.value.counts?.tags)
        }
}
