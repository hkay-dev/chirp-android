package dev.chirpboard.app.feature.obsidian

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import dev.chirpboard.app.core.export.TranscriptExportRecording
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class ObsidianManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
    fun `hasVaultAccess returns true when directory is readable and writable`() =
        runTest {
        val uri = mockk<Uri>()
        val documentFile = mockk<DocumentFile>()
        
        every { DocumentFile.fromTreeUri(context, uri) } returns documentFile
        every { documentFile.canRead() } returns true
        every { documentFile.canWrite() } returns true
        
        assertTrue(manager.hasVaultAccess(uri))
    }

    @Test
    fun `hasVaultAccess returns false when directory is not writable`() =
        runTest {
        val uri = mockk<Uri>()
        val documentFile = mockk<DocumentFile>()
        
        every { DocumentFile.fromTreeUri(context, uri) } returns documentFile
        every { documentFile.canRead() } returns true
        every { documentFile.canWrite() } returns false
        
        assertFalse(manager.hasVaultAccess(uri))
    }

    @Test
    fun `hasVaultAccess returns false when DocumentFile is null`() =
        runTest {
        val uri = mockk<Uri>()
        every { DocumentFile.fromTreeUri(context, uri) } returns null
        assertFalse(manager.hasVaultAccess(uri))
    }

    @Test
    fun `getVaultDisplayName returns name when directory exists`() =
        runTest {
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

    // region TST-008: export success contract (atomic temp write + rename, frontmatter)

    @Test
    fun `export writes markdown atomically via temp file and returns the renamed uri`() = runTest {
        val harness = successExportHarness()

        val result =
            manager.export(
                recording = harness.recording,
                transcript = "the transcript body",
                summary = "a short summary",
                vaultUri = harness.vaultUri,
                tags = listOf("meeting"),
            )

        assertTrue(result.isSuccess)
        assertEquals(harness.tempFileUri, result.getOrNull())

        // The temp document carries a unique suffix and is renamed to the deterministic
        // final filename only after the content was written and synced.
        val expectedFilename =
            buildObsidianExportFilename("Weekly Sync", CREATED_AT_EPOCH_MS, ZoneId.systemDefault())
        assertTrue(harness.createdTempName.captured.startsWith("$expectedFilename.tmp."))
        verifyOrder {
            harness.vaultDir.createFile("text/markdown", harness.createdTempName.captured)
            harness.tempFile.renameTo(expectedFilename)
        }

        // The bytes landed before the rename, and the frontmatter uses the user's LOCAL
        // wall-clock time so daily-note linking matches what the app shows.
        val written = harness.writtenContent()
        val expectedLocalDate =
            LocalDateTime
                .ofInstant(Instant.ofEpochMilli(CREATED_AT_EPOCH_MS), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        assertTrue(written.startsWith("---\n"))
        assertTrue(written.contains("title: Weekly Sync\n"))
        assertTrue(written.contains("date: $expectedLocalDate\n"))
        assertTrue(written.contains("the transcript body"))
        assertTrue(written.contains("a short summary"))
    }

    @Test
    fun `re-export deletes only this recordings own previous note before the rename`() = runTest {
        val harness = successExportHarness()
        val expectedFilename =
            buildObsidianExportFilename("Weekly Sync", CREATED_AT_EPOCH_MS, ZoneId.systemDefault())
        val previousExport = mockk<DocumentFile>()
        every { previousExport.delete() } returns true
        every { harness.vaultDir.findFile(expectedFilename) } returns previousExport

        val result =
            manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        // The filename embeds the created-at timestamp (collision behavior pinned in the
        // filename tests above), so the only file ever replaced is this recording's own
        // earlier export — deleted after the temp write, right before the rename.
        verifyOrder {
            previousExport.delete()
            harness.tempFile.renameTo(expectedFilename)
        }
    }

    @Test
    fun `export falls back to stream copy when SAF rename fails`() = runTest {
        val harness = successExportHarness(renameSucceeds = false)
        val finalFile = mockk<DocumentFile>()
        val finalUri = mockk<Uri>()
        every { finalFile.uri } returns finalUri
        // Re-stub creation: the first call creates the temp document, the fallback's
        // second call creates the final one.
        every {
            harness.vaultDir.createFile("text/markdown", capture(harness.createdTempName))
        } returns harness.tempFile andThen finalFile
        val copied = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(finalUri) } returns copied
        every { contentResolver.openInputStream(harness.tempFileUri) } answers {
            ByteArrayInputStream(harness.writtenContent().toByteArray(Charsets.UTF_8))
        }
        every { harness.tempFile.delete() } returns true

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        assertEquals(finalUri, result.getOrNull())
        // The fallback copies the already-written temp content and removes the temp file.
        assertEquals(harness.writtenContent(), copied.toString(Charsets.UTF_8.name()))
        verify(exactly = 1) { harness.tempFile.delete() }
    }

    @Test
    fun `export cleans up the temp document when the write fails`() = runTest {
        val harness = successExportHarness()
        every { contentResolver.openFileDescriptor(harness.tempFileUri, "w") } returns null
        every { harness.tempFile.delete() } returns true

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        // The failed write never reaches the rename, so no partial note can appear in the
        // vault — and the orphaned temp document is removed.
        assertTrue(result.isFailure)
        verify(exactly = 0) { harness.tempFile.renameTo(any()) }
        verify(exactly = 1) { harness.tempFile.delete() }
    }

    // endregion

    @Test
    fun `sanitizeObsidianFilename replaces slashes colons and reserved characters but keeps emoji`() {
        // Reserved filesystem/SAF characters all collapse to underscores...
        assertEquals("notes_ a_b_c_d_e_f_g_h_i", sanitizeObsidianFilename("notes: a/b\\c*d?e\"f<g>h|i"))
        // ...while unicode the filesystem accepts (emoji, accents) is preserved.
        assertEquals("📝 Standup für heute", sanitizeObsidianFilename("📝 Standup für heute"))
        // Whitespace runs collapse and the result is trimmed.
        assertEquals("a b", sanitizeObsidianFilename("  a \t\n b  "))
        // Overlong titles are capped at 100 characters.
        assertEquals(100, sanitizeObsidianFilename("x".repeat(250)).length)
        // Titles that sanitize to nothing fall back instead of producing ".md".
        assertEquals("Untitled", sanitizeObsidianFilename(""))
    }

    private class SuccessExportHarness(
        val recording: TranscriptExportRecording,
        val vaultUri: Uri,
        val vaultDir: DocumentFile,
        val tempFile: DocumentFile,
        val tempFileUri: Uri,
        val createdTempName: io.mockk.CapturingSlot<String>,
        private val backingFile: java.io.File,
    ) {
        fun writtenContent(): String = backingFile.readText(Charsets.UTF_8)
    }

    /**
     * Wires a writable mock vault whose temp document is backed by a REAL file descriptor,
     * so the bytes the manager writes (and fsyncs) can be asserted afterwards.
     */
    private fun successExportHarness(renameSucceeds: Boolean = true): SuccessExportHarness {
        val recording =
            TranscriptExportRecording(
                title = "Weekly Sync",
                createdAtEpochMs = CREATED_AT_EPOCH_MS,
                durationMs = 90_000L,
                sourceName = "APP",
            )
        val vaultUri = mockk<Uri>()
        val vaultDir = mockk<DocumentFile>()
        val tempFile = mockk<DocumentFile>()
        val tempFileUri = mockk<Uri>()
        val createdTempName = slot<String>()
        val backingFile = temporaryFolder.newFile("export-backing.md")

        every { DocumentFile.fromTreeUri(context, vaultUri) } returns vaultDir
        every { vaultDir.canWrite() } returns true
        every { vaultDir.findFile(any()) } returns null
        every { vaultDir.createFile("text/markdown", capture(createdTempName)) } returns tempFile
        every { tempFile.uri } returns tempFileUri
        every { tempFile.renameTo(any()) } returns renameSucceeds

        val pfd = mockk<ParcelFileDescriptor>()
        every { contentResolver.openFileDescriptor(tempFileUri, "w") } answers {
            // A fresh descriptor per open: the manager closes the stream it wraps around it.
            val raf = RandomAccessFile(backingFile, "rw")
            every { pfd.fileDescriptor } returns raf.fd
            every { pfd.close() } answers { raf.close() }
            pfd
        }

        return SuccessExportHarness(
            recording = recording,
            vaultUri = vaultUri,
            vaultDir = vaultDir,
            tempFile = tempFile,
            tempFileUri = tempFileUri,
            createdTempName = createdTempName,
            backingFile = backingFile,
        )
    }

    private companion object {
        /** 2026-06-11T20:15:00Z — formatted through the system zone in assertions. */
        const val CREATED_AT_EPOCH_MS = 1_781_209_700_000L
    }
}
