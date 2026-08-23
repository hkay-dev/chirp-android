package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.DICTATION_HISTORY_MAX_ENTRIES
import dev.chirpboard.app.data.entity.DictationHistoryEntry
import java.util.Date
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictationHistoryDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: DictationHistoryDao

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.dictationHistoryDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun recordAndObserveNewestFirst() =
        runTest {
            dao.record(DictationHistoryEntry(rawText = "first", processedText = null, createdAt = Date(1_000)))
            dao.record(DictationHistoryEntry(rawText = "second", processedText = "Second.", createdAt = Date(2_000)))

            val entries = dao.observeAll().first()

            assertEquals(2, entries.size)
            assertEquals("second", entries[0].rawText)
            assertEquals("Second.", entries[0].processedText)
            assertEquals("first", entries[1].rawText)
            assertNull(entries[1].processedText)
        }

    @Test
    fun recordPrunesOldestBeyondTheCap() =
        runTest {
            repeat(DICTATION_HISTORY_MAX_ENTRIES + 5) { index ->
                dao.record(
                    DictationHistoryEntry(
                        rawText = "entry $index",
                        processedText = null,
                        createdAt = Date(1_000L + index),
                    ),
                )
            }

            val entries = dao.observeAll().first()

            assertEquals(DICTATION_HISTORY_MAX_ENTRIES, entries.size)
            // Newest survives; the five oldest were pruned.
            assertEquals("entry ${DICTATION_HISTORY_MAX_ENTRIES + 4}", entries.first().rawText)
            assertEquals("entry 5", entries.last().rawText)
        }

    @Test
    fun deleteByIdRemovesOnlyThatEntry() =
        runTest {
            dao.record(DictationHistoryEntry(rawText = "keep", processedText = null, createdAt = Date(1_000)))
            dao.record(DictationHistoryEntry(rawText = "remove", processedText = null, createdAt = Date(2_000)))
            val target = dao.observeAll().first().first { it.rawText == "remove" }

            dao.deleteById(target.id)

            val entries = dao.observeAll().first()
            assertEquals(1, entries.size)
            assertEquals("keep", entries[0].rawText)
        }

    @Test
    fun deleteAllEmptiesTheTable() =
        runTest {
            dao.record(DictationHistoryEntry(rawText = "one", processedText = null))
            dao.record(DictationHistoryEntry(rawText = "two", processedText = null))

            dao.deleteAll()

            assertEquals(0, dao.getCount())
        }
}
