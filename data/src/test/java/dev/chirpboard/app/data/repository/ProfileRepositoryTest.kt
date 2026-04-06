package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.ProfileDao
import dev.chirpboard.app.data.entity.Profile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ProfileRepositoryTest {
    private lateinit var profileDao: ProfileDao
    private lateinit var repository: ProfileRepository

    @Before
    fun setup() {
        profileDao = mockk(relaxed = true)
        repository = ProfileRepository(mockk(relaxed = true), profileDao)
    }

    @Test
    fun `getAllProfiles returns flow from dao`() =
        runTest {
            val expected = listOf(Profile(id = UUID.randomUUID(), name = "Test"))
            coEvery { profileDao.getAllProfiles() } returns flowOf(expected)
            val result = repository.getAllProfiles().first()
            assertEquals(expected, result)
        }

    @Test
    fun `getAllProfilesList returns list from dao`() =
        runTest {
            val expected = listOf(Profile(id = UUID.randomUUID(), name = "Test"))
            coEvery { profileDao.getAllProfilesList() } returns expected
            val result = repository.getAllProfilesList()
            assertEquals(expected, result)
        }

    @Test
    fun `getProfile returns correct profile`() =
        runTest {
            val id = UUID.randomUUID()
            val expected = Profile(id = id, name = "Test Profile")
            coEvery { profileDao.getProfile(id) } returns expected
            val result = repository.getProfile(id)
            assertEquals(expected, result)
            coVerify(exactly = 1) { profileDao.getProfile(id) }
        }

    @Test
    fun `getProfileFlow returns flow from dao`() =
        runTest {
            val id = UUID.randomUUID()
            val expected = Profile(id = id, name = "Test Profile")
            coEvery { profileDao.getProfileFlow(id) } returns flowOf(expected)
            val result = repository.getProfileFlow(id).first()
            assertEquals(expected, result)
        }

    @Test
    fun `createProfile inserts and returns new profile`() =
        runTest {
            coEvery { profileDao.getMaxSortOrder() } returns 5
            val result = repository.createProfile(ProfileRepository.CreateProfileRequest(name = "New Profile"))
            assertNotNull(result)
            assertEquals("New Profile", result.name)
            assertEquals(6, result.sortOrder)
            coVerify(exactly = 1) { profileDao.insert(any()) }
        }

    @Test
    fun `insert delegates to dao`() =
        runTest {
            val profile = Profile(id = UUID.randomUUID(), name = "Test Profile")
            repository.insert(profile)
            coVerify(exactly = 1) { profileDao.insert(profile) }
        }

    @Test
    fun `update delegates to dao`() =
        runTest {
            val profile = Profile(id = UUID.randomUUID(), name = "Test Profile")
            repository.update(profile)
            coVerify(exactly = 1) { profileDao.update(profile) }
        }

    @Test
    fun `delete delegates to dao`() =
        runTest {
            val profile = Profile(id = UUID.randomUUID(), name = "Test Profile")
            repository.delete(profile)
            coVerify(exactly = 1) { profileDao.delete(profile) }
        }

    @Test
    fun `deleteById delegates to dao`() =
        runTest {
            val id = UUID.randomUUID()
            repository.deleteById(id)
            coVerify(exactly = 1) { profileDao.deleteById(id) }
        }

    @Test
    fun `getCount delegates to dao`() =
        runTest {
            coEvery { profileDao.getCount() } returns 10
            val count = repository.getCount()
            assertEquals(10, count)
        }
}
