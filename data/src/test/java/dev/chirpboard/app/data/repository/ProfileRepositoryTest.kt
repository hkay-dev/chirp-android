package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.ProfileDao
import dev.chirpboard.app.data.entity.Profile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileRepositoryTest {
    private lateinit var profileDao: ProfileDao
    private lateinit var repository: ProfileRepository

    @Before
    fun setup() {
        profileDao = mockk(relaxed = true)
        repository = ProfileRepository(profileDao)
    }

    @Test
    fun `createProfile inserts and returns new profile`() =
        runTest {
            coEvery { profileDao.getMaxSortOrder() } returns 5
            val result = repository.createProfile(ProfileRepository.CreateProfileRequest(name = "New Profile"))
            assertNotNull(result)
            assertEquals("New Profile", result.name)
            assertEquals(6, result.sortOrder)
            assertFalse(result.isQuickStartPinned)
            coVerify(exactly = 1) { profileDao.insert(any()) }
        }

    @Test
    fun `createProfile persists quick start pin membership`() =
        runTest {
            coEvery { profileDao.getMaxSortOrder() } returns 0

            val result =
                repository.createProfile(
                    ProfileRepository.CreateProfileRequest(
                        name = "Pinned Profile",
                        quickStartPinned = true,
                    ),
                )

            assertTrue(result.isQuickStartPinned)
            coVerify {
                profileDao.insert(match<Profile> {
                    it.name == "Pinned Profile" && it.isQuickStartPinned
                })
            }
        }
}
