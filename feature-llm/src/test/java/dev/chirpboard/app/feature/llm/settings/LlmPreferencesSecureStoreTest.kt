package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * SEC-2 keyset self-heal: when the AndroidKeyStore master key was invalidated while
 * secure_prefs.xml stayed on disk, EncryptedSharedPreferences.create throws forever.
 * The store must wipe the poisoned file, recreate itself once, and queue a one-shot
 * "re-enter your API keys" notice — instead of leaving API keys permanently unusable.
 */
class LlmPreferencesSecureStoreTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var context: Context
    private lateinit var appPrefs: InMemorySharedPreferences
    private lateinit var securePrefs: InMemorySharedPreferences

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        appPrefs = InMemorySharedPreferences()
        securePrefs = InMemorySharedPreferences()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("chirp", Context.MODE_PRIVATE) } returns appPrefs
        every { context.deleteSharedPreferences("secure_prefs") } returns true

        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers {
            self as MasterKey.Builder
        }
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
        mockkStatic(EncryptedSharedPreferences::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `undecryptable keyset wipes the store recreates it and queues a one-shot notice`() {
        every {
            EncryptedSharedPreferences.create(any<Context>(), any(), any(), any(), any())
        } throws GeneralSecurityException("could not decrypt keyset") andThen securePrefs

        val preferences = LlmPreferences(context)

        // The store healed: it is available again and round-trips a re-entered key.
        assertTrue(preferences.isSecureStorageAvailable())
        verify(exactly = 1) { context.deleteSharedPreferences("secure_prefs") }
        preferences.setApiKeyFor(LlmProvider.GEMINI, "  fresh-key  ")
        assertEquals("fresh-key", preferences.fetchApiKeyFor(LlmProvider.GEMINI))

        // The user is told exactly once that stored keys were lost.
        assertTrue(preferences.consumeSecureStorageResetNotice())
        assertFalse(preferences.consumeSecureStorageResetNotice())
    }

    @Test
    fun `secure store failing even after the reset degrades without crashing or false notices`() {
        every {
            EncryptedSharedPreferences.create(any<Context>(), any(), any(), any(), any())
        } throws GeneralSecurityException("keystore permanently broken")

        val preferences = LlmPreferences(context)

        assertFalse(preferences.isSecureStorageAvailable())
        // Writes and reads degrade to no-ops instead of crashing the settings screen.
        preferences.setApiKeyFor(LlmProvider.GEMINI, "key")
        assertNull(preferences.fetchApiKeyFor(LlmProvider.GEMINI))
        assertFalse(preferences.hasApiKeyFor(LlmProvider.GEMINI))
        // No recreate happened, so no misleading "keys were reset" notice is queued.
        assertFalse(preferences.consumeSecureStorageResetNotice())
    }

    @Test
    fun `healthy secure store is never wiped and queues no notice`() {
        every {
            EncryptedSharedPreferences.create(any<Context>(), any(), any(), any(), any())
        } returns securePrefs

        val preferences = LlmPreferences(context)

        assertTrue(preferences.isSecureStorageAvailable())
        verify(exactly = 0) { context.deleteSharedPreferences(any()) }
        assertFalse(preferences.consumeSecureStorageResetNotice())
    }

    /** Minimal in-memory SharedPreferences honoring commit/apply semantics. */
    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
