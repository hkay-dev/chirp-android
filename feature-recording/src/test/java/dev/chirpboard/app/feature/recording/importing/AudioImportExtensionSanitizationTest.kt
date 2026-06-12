package dev.chirpboard.app.feature.recording.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SEC-9: the extension derived from an untrusted shared URI must never smuggle
 * path separators (or anything else) into the imported file name.
 */
class AudioImportExtensionSanitizationTest {
    @Test
    fun `plain audio extensions pass through lowercased`() {
        assertEquals("m4a", sanitizeImportedAudioExtension("m4a"))
        assertEquals("mp3", sanitizeImportedAudioExtension("MP3"))
        assertEquals("flac", sanitizeImportedAudioExtension("flac"))
    }

    @Test
    fun `path separators and traversal sequences are stripped`() {
        assertEquals("etcpa", sanitizeImportedAudioExtension("../../etc/passwd"))
        assertEquals("xwav", sanitizeImportedAudioExtension("x/../wav"))
        assertEquals("wav", sanitizeImportedAudioExtension("/wav"))
        assertEquals("wav", sanitizeImportedAudioExtension("wa\\v"))
    }

    @Test
    fun `dots and separator-only candidates yield null`() {
        assertNull(sanitizeImportedAudioExtension("..."))
        assertNull(sanitizeImportedAudioExtension("/../"))
        assertNull(sanitizeImportedAudioExtension(""))
        assertNull(sanitizeImportedAudioExtension(null))
    }

    @Test
    fun `length is capped`() {
        assertEquals("aaaaa", sanitizeImportedAudioExtension("aaaaaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun `mime subtype punctuation is removed`() {
        assertEquals("xwav", sanitizeImportedAudioExtension("x-wav"))
        assertEquals("mpeg", sanitizeImportedAudioExtension("mpeg"))
    }
}
