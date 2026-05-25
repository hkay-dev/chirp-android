package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.WordReplacement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WordReplacementDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: WordReplacementDao

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.wordReplacementDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetEnabledReplacements() = runTest {
        dao.insert(
            WordReplacement(
                id = UUID.randomUUID(),
                original = "Chirpboard",
                replacement = "Chirp Board",
                enabled = true,
            ),
        )
        dao.insert(
            WordReplacement(
                id = UUID.randomUUID(),
                original = "disabled",
                replacement = "skip",
                enabled = false,
            ),
        )

        val enabled = dao.getEnabledReplacements()
        assertEquals(1, enabled.size)
        assertEquals("Chirp Board", enabled.single().replacement)
    }

    @Test
    fun getAllReplacementsFlow() = runTest {
        dao.insert(
            WordReplacement(
                original = "alpha",
                replacement = "A",
            ),
        )
        dao.insert(
            WordReplacement(
                original = "beta",
                replacement = "B",
            ),
        )

        val replacements = dao.getAllReplacements().first()
        assertEquals(2, replacements.size)
        assertEquals("alpha", replacements[0].original)
    }
}
