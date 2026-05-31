package dev.chirpboard.app.feature.obsidian

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.chirpboard.app.core.export.TranscriptExportRecording
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObsidianManagerTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var manager: ObsidianManager

    @Before
    fun setup() {
        context = mockk()
        contentResolver = mockk()
        every { context.contentResolver } returns contentResolver
        
        mockkStatic(DocumentFile::class)
        manager = ObsidianManager(context)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `hasVaultAccess returns true when directory is readable and writable`() {
        val uri = mockk<Uri>()
        val documentFile = mockk<DocumentFile>()
        
        every { DocumentFile.fromTreeUri(context, uri) } returns documentFile
        every { documentFile.canRead() } returns true
        every { documentFile.canWrite() } returns true
        
        assertTrue(manager.hasVaultAccess(uri))
    }

    @Test
    fun `hasVaultAccess returns false when directory is not writable`() {
        val uri = mockk<Uri>()
        val documentFile = mockk<DocumentFile>()
        
        every { DocumentFile.fromTreeUri(context, uri) } returns documentFile
        every { documentFile.canRead() } returns true
        every { documentFile.canWrite() } returns false
        
        assertFalse(manager.hasVaultAccess(uri))
    }

    @Test
    fun `hasVaultAccess returns false when DocumentFile is null`() {
        val uri = mockk<Uri>()
        every { DocumentFile.fromTreeUri(context, uri) } returns null
        assertFalse(manager.hasVaultAccess(uri))
    }

    @Test
    fun `getVaultDisplayName returns name when directory exists`() {
        val uri = mockk<Uri>()
        val documentFile = mockk<DocumentFile>()
        
        every { DocumentFile.fromTreeUri(context, uri) } returns documentFile
        every { documentFile.name } returns "My Vault"
        
        assertEquals("My Vault", manager.getVaultDisplayName(uri))
    }

    @Test
    fun `export fails if no vault access`() = runTest {
        val uri = mockk<Uri>()
        val recording =
            TranscriptExportRecording(
                title = "Recording",
                createdAtEpochMs = 0L,
                durationMs = 1_000L,
                sourceName = "app",
            )
        
        every { DocumentFile.fromTreeUri(context, uri) } returns null
        
        val result = manager.export(recording, "transcript", "summary", uri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
