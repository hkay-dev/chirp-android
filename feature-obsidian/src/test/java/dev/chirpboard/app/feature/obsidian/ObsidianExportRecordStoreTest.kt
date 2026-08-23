package dev.chirpboard.app.feature.obsidian

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ObsidianExportRecordStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var store: ObsidianExportRecordStore

    @Before
    fun setup() {
        val testDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(tmpFolder.root, "test_preferences.preferences_pb") }
        )
        store = ObsidianExportRecordStore(testDataStore)
    }

    @Test
    fun `an unexported note has no record`() = testScope.runTest {
        assertNull(store.lastExport("Note (2026-01-01 000000).md"))
    }

    @Test
    fun `recordExport round-trips the written filename and hash`() = testScope.runTest {
        val base = "Note (2026-01-01 000000).md"
        val record = ObsidianExportRecord("$base.conflict", sha256Hex("body"))

        store.recordExport(base, record)

        assertEquals(record, store.lastExport(base))
    }

    @Test
    fun `re-exporting a note replaces its record instead of accumulating`() = testScope.runTest {
        val base = "Note (2026-01-01 000000).md"
        store.recordExport(base, ObsidianExportRecord(base, sha256Hex("first")))
        store.recordExport(base, ObsidianExportRecord(base, sha256Hex("second")))

        assertEquals(sha256Hex("second"), store.lastExport(base)?.contentHash)
    }

    @Test
    fun `records are capped so the map cannot grow without bound`() = testScope.runTest {
        // 250 exported notes against a 200-entry cap: the oldest are evicted, the newest stay.
        repeat(250) { index ->
            store.recordExport("Note $index.md", ObsidianExportRecord("Note $index.md", sha256Hex("$index")))
        }

        assertNull(store.lastExport("Note 0.md"))
        assertNull(store.lastExport("Note 49.md"))
        assertEquals(sha256Hex("50"), store.lastExport("Note 50.md")?.contentHash)
        assertEquals(sha256Hex("249"), store.lastExport("Note 249.md")?.contentHash)
    }

    @Test
    fun `encoding round-trips names containing the separator-adjacent characters`() {
        val records =
            mapOf(
                "Weekly Sync (2026-01-01 000000).md" to
                    ObsidianExportRecord("Weekly Sync (2026-01-01 000000).md", sha256Hex("a")),
                "Idea (2026-01-02 010203).md" to
                    ObsidianExportRecord("Idea (2026-01-02 010203) (conflict 2).md", sha256Hex("b")),
            )

        assertEquals(records, decodeExportRecords(encodeExportRecords(records)))
    }

    @Test
    fun `decoding tolerates empty and malformed lines`() {
        assertEquals(emptyMap<String, ObsidianExportRecord>(), decodeExportRecords(null))
        assertEquals(emptyMap<String, ObsidianExportRecord>(), decodeExportRecords(""))
        // A truncated line is dropped; the intact one survives.
        val decoded = decodeExportRecords("garbage\nhash\tNote.md\tNote.md\n")
        assertEquals(mapOf("Note.md" to ObsidianExportRecord("Note.md", "hash")), decoded)
    }
}
