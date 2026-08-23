package dev.chirpboard.app.feature.obsidian

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.chirpboard.app.core.export.TranscriptExportRecording
import io.mockk.coEvery
import io.mockk.coVerify
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
    private lateinit var exportRecords: ObsidianExportRecordStore
    private lateinit var manager: ObsidianManager

    @Before
    fun setup() {
        context = mockk()
        contentResolver = mockk()
        every { context.contentResolver } returns contentResolver
        exportRecords = mockk()
        coEvery { exportRecords.lastExport(any()) } returns null
        coEvery { exportRecords.recordExport(any(), any()) } just runs

        // The manager logs on every preservation and fallback path; android.util.Log is a
        // throwing stub in unit tests, so a log line would fail the export under test.
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        mockkStatic(DocumentFile::class)
        manager = ObsidianManager(context, exportRecords)
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
        assertEquals(harness.tempFileUri, result.getOrNull()?.uri)
        assertEquals(ObsidianExportDisposition.CREATED, result.getOrNull()?.disposition)

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
        assertTrue(written.contains("title: \"Weekly Sync\"\n"))
        assertTrue(written.contains("date: $expectedLocalDate\n"))
        assertTrue(written.contains("the transcript body"))
        assertTrue(written.contains("a short summary"))
    }

    @Test
    fun `re-export moves the previous note aside and deletes it only after the rename`() = runTest {
        val harness = successExportHarness()
        val expectedFilename = expectedExportFilename()
        val previousExport = unmodifiedPreviousExport(expectedFilename)
        every { previousExport.delete() } returns true
        every { harness.vaultDir.listFiles() } returns arrayOf(previousExport)

        val result =
            manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        assertEquals(ObsidianExportDisposition.UPDATED, result.getOrNull()?.disposition)
        // The previous note is parked under a backup name and removed only once the new
        // note holds the final name — it exists under some name at every instant.
        verifyOrder {
            previousExport.renameTo(match { it.startsWith("$expectedFilename.bak.") })
            harness.tempFile.renameTo(expectedFilename)
            previousExport.delete()
        }
        // The hash of the bytes just written is what the next export compares against.
        coVerify {
            exportRecords.recordExport(
                expectedFilename,
                ObsidianExportRecord(expectedFilename, sha256Hex(harness.writtenContent())),
            )
        }
    }

    @Test
    fun `a failed replacement restores the previous note instead of losing it`() = runTest {
        val harness = successExportHarness(renameSucceeds = false)
        val expectedFilename = expectedExportFilename()
        val previousExport = unmodifiedPreviousExport(expectedFilename)
        every { harness.vaultDir.listFiles() } returns arrayOf(previousExport)
        // Temp rename fails AND the fallback can't create the final document (grant
        // revoked mid-export): the previous note must be renamed back.
        every {
            harness.vaultDir.createFile("text/markdown", capture(harness.createdTempName))
        } returns harness.tempFile andThen null
        every { harness.tempFile.delete() } returns true

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ObsidianVaultAccessException)
        verifyOrder {
            previousExport.renameTo(match { it.startsWith("$expectedFilename.bak.") })
            previousExport.renameTo(expectedFilename)
        }
        verify(exactly = 1) { harness.tempFile.delete() }
    }

    @Test
    fun `when the provider cannot rename the previous note it is overwritten in place`() = runTest {
        val harness = successExportHarness()
        val expectedFilename = expectedExportFilename()
        val previousExport = unmodifiedPreviousExport(expectedFilename)
        val previousUri = previousExport.uri
        every { previousExport.renameTo(any()) } returns false
        every { harness.vaultDir.listFiles() } returns arrayOf(previousExport)
        val copied = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(previousUri, "wt") } returns copied
        every { contentResolver.openInputStream(harness.tempFileUri) } answers {
            ByteArrayInputStream(harness.writtenContent().toByteArray(Charsets.UTF_8))
        }
        every { harness.tempFile.delete() } returns true

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        assertEquals(previousUri, result.getOrNull()?.uri)
        assertEquals(harness.writtenContent(), copied.toString(Charsets.UTF_8.name()))
        verify(exactly = 0) { previousExport.delete() }
        verify(exactly = 1) { harness.tempFile.delete() }
    }

    @Test
    fun `stale temp and backup leftovers from crashed exports are swept`() = runTest {
        val harness = successExportHarness()
        val expectedFilename =
            buildObsidianExportFilename("Weekly Sync", CREATED_AT_EPOCH_MS, ZoneId.systemDefault())
        val staleTemp = mockk<DocumentFile>()
        every { staleTemp.name } returns "$expectedFilename.tmp.deadbeef"
        every { staleTemp.uri } returns mockk()
        every { staleTemp.delete() } returns true
        val staleBackup = mockk<DocumentFile>()
        every { staleBackup.name } returns "$expectedFilename.bak.cafebabe"
        every { staleBackup.uri } returns mockk()
        every { staleBackup.delete() } returns true
        val otherNote = mockk<DocumentFile>()
        every { otherNote.name } returns "Unrelated (2026-01-01 000000).md"
        every { otherNote.uri } returns mockk()
        every { harness.vaultDir.listFiles() } returns arrayOf(staleTemp, staleBackup, otherNote)

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { staleTemp.delete() }
        verify(exactly = 1) { staleBackup.delete() }
        verify(exactly = 0) { otherNote.delete() }
    }

    @Test
    fun `the sweep keeps the in-flight temp file when the provider renamed it`() = runTest {
        val harness = successExportHarness()
        val expectedFilename =
            buildObsidianExportFilename("Weekly Sync", CREATED_AT_EPOCH_MS, ZoneId.systemDefault())
        // ExternalStorageProvider appends the mime extension to the requested display name, so
        // the created document no longer matches the name the export asked for — only its uri.
        every { harness.tempFile.name } returns "$expectedFilename.tmp.deadbeef.md"
        every { harness.tempFile.delete() } returns true
        every { harness.vaultDir.listFiles() } returns arrayOf(harness.tempFile)

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        verify(exactly = 0) { harness.tempFile.delete() }
        assertTrue(harness.writtenContent().contains("transcript"))
    }

    @Test
    fun `a vault that refuses to create files fails as an access error`() = runTest {
        val harness = successExportHarness()
        every { harness.vaultDir.createFile("text/markdown", any()) } returns null

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ObsidianVaultAccessException)
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
        every { contentResolver.openOutputStream(finalUri, "wt") } returns copied
        every { contentResolver.openInputStream(harness.tempFileUri) } answers {
            ByteArrayInputStream(harness.writtenContent().toByteArray(Charsets.UTF_8))
        }
        every { harness.tempFile.delete() } returns true

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        assertEquals(finalUri, result.getOrNull()?.uri)
        // The fallback copies the already-written temp content and removes the temp file.
        assertEquals(harness.writtenContent(), copied.toString(Charsets.UTF_8.name()))
        verify(exactly = 1) { harness.tempFile.delete() }
    }

    @Test
    fun `a failed in-place overwrite keeps the temp file as the surviving copy`() = runTest {
        // Provider supports neither rename, so the previous note is overwritten in place with
        // mode "wt" — which truncates it before anything is copied.
        val harness = successExportHarness(renameSucceeds = false)
        val previousExport = unmodifiedPreviousExport(expectedExportFilename())
        val previousUri = previousExport.uri
        every { previousExport.renameTo(any()) } returns false
        every { harness.vaultDir.listFiles() } returns arrayOf(previousExport)
        every { contentResolver.openOutputStream(previousUri, "wt") } returns null
        every { harness.tempFile.delete() } returns true

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isFailure)
        // Deleting the temp here would leave the truncated note as the only copy.
        verify(exactly = 0) { harness.tempFile.delete() }
    }

    @Test
    fun `a failed fallback copy removes the half-written final file`() = runTest {
        val harness = successExportHarness(renameSucceeds = false)
        val finalFile = mockk<DocumentFile>()
        val finalUri = mockk<Uri>()
        every { finalFile.uri } returns finalUri
        every { finalFile.delete() } returns true
        every {
            harness.vaultDir.createFile("text/markdown", capture(harness.createdTempName))
        } returns harness.tempFile andThen finalFile
        every { contentResolver.openOutputStream(finalUri, "wt") } returns null
        every { harness.tempFile.delete() } returns true

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isFailure)
        verify(exactly = 1) { finalFile.delete() }
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

    @Test
    fun `sanitizeObsidianFilename strips control characters and truncates at character boundaries`() {
        // Non-whitespace control characters (SOH, BEL, DEL) are removed outright.
        assertEquals("abc", sanitizeObsidianFilename("a" + Char(1) + "b" + Char(7) + "c" + Char(127)))
        // Truncation never splits a surrogate pair: an emoji straddling the 100-unit cap
        // is dropped whole instead of leaving a lone surrogate in the filename.
        val truncated = sanitizeObsidianFilename("x".repeat(99) + "😀")
        assertEquals(99, truncated.length)
        assertFalse(truncated.last().isHighSurrogate())
    }

    // region DAT: re-export must never destroy edits the user made in the vault

    @Test
    fun `a note edited in the vault is preserved and the export goes to a conflict copy`() = runTest {
        val harness = successExportHarness()
        val expectedFilename = expectedExportFilename()
        // Bookkeeping says we last wrote "exported body"; the vault holds the user's edit.
        val previousExport = unmodifiedPreviousExport(expectedFilename, body = "body with user notes")
        coEvery { exportRecords.lastExport(expectedFilename) } returns
            ObsidianExportRecord(expectedFilename, sha256Hex("exported body"))
        every { harness.vaultDir.listFiles() } returns arrayOf(previousExport)

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        val conflictFilename = expectedFilename.removeSuffix(".md") + " (conflict 1).md"
        assertTrue(result.isSuccess)
        assertEquals(ObsidianExportDisposition.CONFLICT, result.getOrNull()?.disposition)
        assertEquals(conflictFilename, result.getOrNull()?.filename)
        // The user's note is neither renamed aside nor deleted, and the new content lands
        // under the conflict name.
        verify(exactly = 0) { previousExport.renameTo(any()) }
        verify(exactly = 0) { previousExport.delete() }
        verify(exactly = 1) { harness.tempFile.renameTo(conflictFilename) }
        // The next export tracks the conflict copy, so repeated re-exports update that copy
        // instead of piling up conflict 2, 3, 4...
        coVerify {
            exportRecords.recordExport(
                expectedFilename,
                ObsidianExportRecord(conflictFilename, sha256Hex(harness.writtenContent())),
            )
        }
    }

    @Test
    fun `an export predating hash bookkeeping is treated as edited`() = runTest {
        val harness = successExportHarness()
        val expectedFilename = expectedExportFilename()
        val previousExport = mockk<DocumentFile>()
        every { previousExport.name } returns expectedFilename
        every { previousExport.uri } returns mockk()
        every { harness.vaultDir.listFiles() } returns arrayOf(previousExport)
        coEvery { exportRecords.lastExport(expectedFilename) } returns null

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        // No hash to compare against, so the note might hold edits — preserve it.
        assertEquals(ObsidianExportDisposition.CONFLICT, result.getOrNull()?.disposition)
        verify(exactly = 0) { previousExport.renameTo(any()) }
    }

    @Test
    fun `a conflict copy this app wrote is updated in place on the next export`() = runTest {
        val harness = successExportHarness()
        val expectedFilename = expectedExportFilename()
        val conflictFilename = expectedFilename.removeSuffix(".md") + " (conflict 1).md"
        val userEdited = mockk<DocumentFile>()
        every { userEdited.name } returns expectedFilename
        every { userEdited.uri } returns mockk()
        val conflictCopy = unmodifiedPreviousExport(conflictFilename, trackedAs = expectedFilename)
        every { conflictCopy.delete() } returns true
        every { harness.vaultDir.listFiles() } returns arrayOf(userEdited, conflictCopy)

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertEquals(ObsidianExportDisposition.UPDATED, result.getOrNull()?.disposition)
        assertEquals(conflictFilename, result.getOrNull()?.filename)
        verify(exactly = 1) { harness.tempFile.renameTo(conflictFilename) }
        verify(exactly = 0) { userEdited.renameTo(any()) }
        // The backup name keeps the base prefix so the leftover sweep can still find it.
        verify { conflictCopy.renameTo(match { it.startsWith("$expectedFilename.bak.") }) }
    }

    @Test
    fun `the conflict counter skips names already taken in the vault`() {
        val existing = setOf("Note (2026-01-01 000000).md", "Note (2026-01-01 000000) (conflict 1).md")
        assertEquals(
            "Note (2026-01-01 000000) (conflict 2).md",
            buildObsidianConflictFilename("Note (2026-01-01 000000).md", existing),
        )
        assertEquals(
            "Note (2026-01-01 000000) (conflict 1).md",
            buildObsidianConflictFilename("Note (2026-01-01 000000).md", emptySet()),
        )
    }

    @Test
    fun `export target replaces only a document that still hashes to the last export`() {
        val base = "Note (2026-01-01 000000).md"
        val record = ObsidianExportRecord(base, sha256Hex("exported"))

        val unchanged =
            decideObsidianExportTarget(base, record, setOf(base)) { sha256Hex("exported") }
        assertEquals(ObsidianExportDisposition.UPDATED, unchanged.disposition)
        assertEquals(base, unchanged.filename)
        assertTrue(unchanged.replacesExisting)

        val edited = decideObsidianExportTarget(base, record, setOf(base)) { sha256Hex("edited") }
        assertEquals(ObsidianExportDisposition.CONFLICT, edited.disposition)
        assertFalse(edited.replacesExisting)

        // An unreadable document is indistinguishable from an edited one, so preserve it.
        val unreadable = decideObsidianExportTarget(base, record, setOf(base)) { null }
        assertEquals(ObsidianExportDisposition.CONFLICT, unreadable.disposition)
    }

    @Test
    fun `export target creates the canonical note when nothing of ours is in the vault`() {
        val base = "Note (2026-01-01 000000).md"

        val firstExport = decideObsidianExportTarget(base, null, emptySet()) { null }
        assertEquals(ObsidianExportDisposition.CREATED, firstExport.disposition)
        assertEquals(base, firstExport.filename)

        // Our tracked conflict copy was deleted but the user's note is still there: recreating
        // the canonical name would overwrite the user's file.
        val record = ObsidianExportRecord("$base.gone", sha256Hex("exported"))
        val userNoteRemains = decideObsidianExportTarget(base, record, setOf(base)) { null }
        assertEquals(ObsidianExportDisposition.CONFLICT, userNoteRemains.disposition)

        // Nothing is there at all: write the canonical note again.
        val vaultEmptied = decideObsidianExportTarget(base, record, emptySet()) { null }
        assertEquals(ObsidianExportDisposition.CREATED, vaultEmptied.disposition)
        assertEquals(base, vaultEmptied.filename)
    }

    @Test
    fun `sha256Hex is stable and content sensitive`() {
        assertEquals(sha256Hex("abc"), sha256Hex("abc"))
        assertTrue(sha256Hex("abc") != sha256Hex("abd"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }

    // endregion

    // region PERF: one cursor over the vault root instead of a query per child

    @Test
    fun `the vault scan reads every child name from a single cursor query`() = runTest {
        val harness = successExportHarness()
        val expectedFilename = expectedExportFilename()
        mockkStatic(DocumentsContract::class)
        val vaultDirUri = mockk<Uri>()
        val childrenUri = mockk<Uri>()
        every { harness.vaultDir.uri } returns vaultDirUri
        every { DocumentsContract.getDocumentId(vaultDirUri) } returns "vault"
        every { DocumentsContract.getDocumentId(harness.tempFileUri) } returns "temp"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(vaultDirUri, "vault") } returns childrenUri
        val staleUri = mockk<Uri>()
        every { DocumentsContract.buildDocumentUriUsingTree(vaultDirUri, "stale") } returns staleUri
        val staleLeftover = mockk<DocumentFile>()
        every { staleLeftover.delete() } returns true
        every { DocumentFile.fromTreeUri(context, staleUri) } returns staleLeftover

        // Rows: the in-flight temp document, an unrelated note, and a crashed export's leftover.
        val cursor = mockk<Cursor>()
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, false)
        every { cursor.getString(0) } returnsMany listOf("temp", "other", "stale")
        every { cursor.getString(1) } returnsMany
            listOf("whatever.md", "Unrelated.md", "$expectedFilename.tmp.deadbeef")
        every { cursor.close() } just runs
        every { contentResolver.query(childrenUri, any(), null, null, null) } returns cursor

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        // No per-child DocumentFile walk, and only the leftover was materialized.
        verify(exactly = 0) { harness.vaultDir.listFiles() }
        verify(exactly = 1) { contentResolver.query(childrenUri, any(), null, null, null) }
        verify(exactly = 1) { staleLeftover.delete() }
        // The in-flight temp document is skipped by document id, never swept.
        verify(exactly = 0) { harness.tempFile.delete() }
    }

    @Test
    fun `a failed child query falls back to the DocumentFile walk`() = runTest {
        val harness = successExportHarness()
        mockkStatic(DocumentsContract::class)
        val vaultDirUri = mockk<Uri>()
        every { harness.vaultDir.uri } returns vaultDirUri
        every { DocumentsContract.getDocumentId(vaultDirUri) } returns "vault"
        every { DocumentsContract.getDocumentId(harness.tempFileUri) } returns "temp"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(vaultDirUri, "vault") } returns mockk()
        every { contentResolver.query(any(), any(), null, null, null) } returns null

        val result = manager.export(harness.recording, "transcript", null, harness.vaultUri)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { harness.vaultDir.listFiles() }
    }

    // endregion

    /** The deterministic note name the harness recording maps to. */
    private fun expectedExportFilename(): String =
        buildObsidianExportFilename("Weekly Sync", CREATED_AT_EPOCH_MS, ZoneId.systemDefault())

    /**
     * A vault document that holds exactly what this app last exported, so a re-export is
     * allowed to replace it. [trackedAs] is the base filename the bookkeeping is keyed by,
     * which differs from [filename] once a conflict copy is being tracked.
     */
    private fun unmodifiedPreviousExport(
        filename: String,
        body: String = "previously exported body",
        trackedAs: String = filename,
    ): DocumentFile {
        val document = mockk<DocumentFile>()
        val uri = mockk<Uri>()
        every { document.name } returns filename
        every { document.uri } returns uri
        every { document.renameTo(any()) } returns true
        every { contentResolver.openInputStream(uri) } answers {
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }
        coEvery { exportRecords.lastExport(trackedAs) } returns
            ObsidianExportRecord(filename, sha256Hex(body))
        return document
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
        every { vaultDir.listFiles() } returns emptyArray()
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
