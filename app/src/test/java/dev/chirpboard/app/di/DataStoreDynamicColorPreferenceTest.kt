package dev.chirpboard.app.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreDynamicColorPreferenceTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var preference: DataStoreDynamicColorPreference

    @Before
    fun setup() {
        val testDataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { File(tmpFolder.root, "appearance_test.preferences_pb") },
            )
        preference = DataStoreDynamicColorPreference(testDataStore)
    }

    @Test
    fun `defaults to brand palette (dynamic color off) when unset`() = testScope.runTest {
        preference.useDynamicColor.test {
            assertEquals(DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR, awaitItem())
        }
    }

    @Test
    fun `default constant is false so the brand palette is the default`() {
        // DECISIONS Color/brand: the brand lavender palette must be the default for cohesion.
        assertEquals(false, DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR)
    }

    @Test
    fun `setUseDynamicColor persists and emits the new value`() = testScope.runTest {
        preference.useDynamicColor.test {
            assertEquals(false, awaitItem())

            preference.setUseDynamicColor(true)
            assertEquals(true, awaitItem())

            preference.setUseDynamicColor(false)
            assertEquals(false, awaitItem())
        }
    }
}
