package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Profile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ProfileDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.profileDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetProfile() = runTest {
        val profile = Profile(id = UUID.randomUUID(), name = "Test Profile", sortOrder = 1)
        dao.insert(profile)

        val loaded = dao.getProfile(profile.id)
        assertEquals(profile.name, loaded?.name)
    }

    @Test
    fun getAllProfilesFlow() = runTest {
        val profile1 = Profile(id = UUID.randomUUID(), name = "Profile A", sortOrder = 1)
        val profile2 = Profile(id = UUID.randomUUID(), name = "Profile B", sortOrder = 2)
        
        dao.insert(profile1)
        dao.insert(profile2)

        val flow = dao.getAllProfiles()
        val list = flow.first()
        
        assertEquals(2, list.size)
        assertEquals("Profile A", list[0].name)
    }
}
