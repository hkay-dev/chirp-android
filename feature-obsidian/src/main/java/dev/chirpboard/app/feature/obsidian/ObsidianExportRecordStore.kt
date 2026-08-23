package dev.chirpboard.app.feature.obsidian

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.feature.obsidian.settings.ObsidianDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the last successful export of a note put in the vault: the document it wrote and a
 * hash of the exact bytes it wrote there. A vault document that no longer hashes to
 * [contentHash] was edited in Obsidian, so overwriting it would destroy the user's work.
 */
data class ObsidianExportRecord(
    val filename: String,
    val contentHash: String,
)

/**
 * Per-note export bookkeeping, keyed by the deterministic export filename a recording maps
 * to. Lives in the Obsidian settings DataStore next to the vault URI so it is cleared with
 * the rest of the integration's state.
 *
 * The map is capped at [MAX_EXPORT_RECORDS] entries, least recently exported dropped first.
 * A dropped record is not a correctness problem: an unknown note is treated as edited, which
 * costs a conflict copy but never loses vault content.
 */
@Singleton
class ObsidianExportRecordStore
    @Inject
    constructor(
        @ObsidianDataStore private val dataStore: DataStore<Preferences>,
    ) {
        companion object {
            private const val MAX_EXPORT_RECORDS = 200
            private val EXPORT_RECORDS = stringPreferencesKey("export_records")
        }

        /** The last export of [baseFilename], or null when this app has not exported it. */
        suspend fun lastExport(baseFilename: String): ObsidianExportRecord? =
            decodeExportRecords(dataStore.data.first()[EXPORT_RECORDS])[baseFilename]

        /** Records what an export of [baseFilename] just wrote, evicting the oldest entries. */
        suspend fun recordExport(
            baseFilename: String,
            record: ObsidianExportRecord,
        ) {
            dataStore.edit { preferences ->
                val records = LinkedHashMap(decodeExportRecords(preferences[EXPORT_RECORDS]))
                // Remove first so the re-insert moves this note to the most-recent end.
                records.remove(baseFilename)
                records[baseFilename] = record
                while (records.size > MAX_EXPORT_RECORDS) {
                    records.remove(records.keys.first())
                }
                preferences[EXPORT_RECORDS] = encodeExportRecords(records)
            }
        }
    }

private const val EXPORT_RECORD_FIELD_SEPARATOR = "\t"
private const val EXPORT_RECORD_FIELD_COUNT = 3

/**
 * One record per line, `hash<TAB>filename<TAB>baseFilename`. Export filenames come from
 * [sanitizeObsidianFilename], which collapses every whitespace run to a single space, so
 * neither separator can appear inside a field.
 */
internal fun encodeExportRecords(records: Map<String, ObsidianExportRecord>): String =
    records.entries.joinToString("\n") { (baseFilename, record) ->
        listOf(record.contentHash, record.filename, baseFilename)
            .joinToString(EXPORT_RECORD_FIELD_SEPARATOR)
    }

/** Inverse of [encodeExportRecords]; malformed lines are dropped rather than failing the read. */
internal fun decodeExportRecords(encoded: String?): Map<String, ObsidianExportRecord> {
    if (encoded.isNullOrEmpty()) return emptyMap()
    val records = LinkedHashMap<String, ObsidianExportRecord>()
    for (line in encoded.lineSequence()) {
        if (line.isEmpty()) continue
        val fields = line.split(EXPORT_RECORD_FIELD_SEPARATOR, limit = EXPORT_RECORD_FIELD_COUNT)
        if (fields.size != EXPORT_RECORD_FIELD_COUNT) continue
        val (contentHash, filename, baseFilename) = fields
        if (baseFilename.isEmpty() || filename.isEmpty() || contentHash.isEmpty()) continue
        records[baseFilename] = ObsidianExportRecord(filename = filename, contentHash = contentHash)
    }
    return records
}
