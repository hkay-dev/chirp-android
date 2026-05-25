package dev.chirpboard.app.feature.obsidian.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
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
class ObsidianPreferencesTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var preferences: ObsidianPreferences

    @Before
    fun setup() {
        val testDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(tmpFolder.root, "test_preferences.preferences_pb") }
        )
        preferences = ObsidianPreferences(testDataStore)
    }

    @Test
    fun `setGlobalVaultUri updates flow`() = testScope.runTest {
        preferences.globalVaultUri.test {
            assertNull(awaitItem())
            
            preferences.setGlobalVaultUri("content://test.uri")
            assertEquals("content://test.uri", awaitItem())
            
            preferences.setGlobalVaultUri(null)
            assertNull(awaitItem())
        }
    }

    @Test
    fun `setAutoExportEnabled updates flow`() = testScope.runTest {
        preferences.autoExportEnabled.test {
            assertEquals(false, awaitItem())
            
            preferences.setAutoExportEnabled(true)
            assertEquals(true, awaitItem())
            
            preferences.setAutoExportEnabled(false)
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `clearAll removes all preferences`() = testScope.runTest {
        preferences.setGlobalVaultUri("content://test")
        preferences.setAutoExportEnabled(true)
        
        preferences.clearAll()
        
        preferences.globalVaultUri.test {
            assertNull(awaitItem())
        }
        preferences.autoExportEnabled.test {
            assertEquals(false, awaitItem())
        }
    }
}
