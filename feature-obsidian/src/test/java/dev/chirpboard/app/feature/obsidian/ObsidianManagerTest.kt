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
    fun `export filename embeds local created-at timestamp so same titles never collide`() {
        val zone = java.time.ZoneId.of("UTC")
        val first = buildObsidianExportFilename("Idea", 0L, zone)
        val second = buildObsidianExportFilename("Idea", 61_000L, zone)

        assertEquals("Idea (1970-01-01 000000).md", first)
        assertEquals("Idea (1970-01-01 000101).md", second)
        assertTrue(first != second)
    }

    @Test
    fun `export filename is deterministic for the same recording`() {
        val zone = java.time.ZoneId.of("UTC")
        assertEquals(
            buildObsidianExportFilename("Idea", 12_345L, zone),
            buildObsidianExportFilename("Idea", 12_345L, zone),
        )
    }

    @Test
    fun `export filename uses the provided zone for the calendar date`() {
        // 2026-06-12 04:30 UTC is still 2026-06-11 in UTC-7: the local date must win
        // so daily-note grouping matches what the user saw on the clock.
        val epochMs = 1_781_238_600_000L // 2026-06-12T04:30:00Z
        val utc = buildObsidianExportFilename("Note", epochMs, java.time.ZoneId.of("UTC"))
        val la = buildObsidianExportFilename("Note", epochMs, java.time.ZoneId.of("America/Los_Angeles"))

        assertEquals("Note (2026-06-12 043000).md", utc)
        assertEquals("Note (2026-06-11 213000).md", la)
    }

    @Test
    fun `sanitizeObsidianFilename strips invalid characters and falls back when blank`() {
        assertEquals("a_b_c", sanitizeObsidianFilename("a/b\\c"))
        assertEquals("___", sanitizeObsidianFilename("???"))
        assertEquals("Untitled", sanitizeObsidianFilename("   "))
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
