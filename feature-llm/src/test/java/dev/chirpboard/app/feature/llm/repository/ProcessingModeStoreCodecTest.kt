package dev.chirpboard.app.feature.llm.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DAT-013: the processing-mode JSON blobs are stored in a versioned envelope, read tolerantly
 * from the legacy unversioned shape, and null-validated after Gson (which can put nulls into
 * non-null Kotlin fields via unsafe allocation).
 */
class ProcessingModeStoreCodecTest {
    private val preset =
        StoredCustomPreset(
            id = "user_1",
            name = "My preset",
            prompt = "Do the thing",
            originalPrompt = "Do the original thing",
        )

    // region overrides

    @Test
    fun `overrides round-trip through the versioned envelope`() {
        val overrides = mapOf("proofread" to "P prompt", "custom" to "C prompt")
        val json = ProcessingModeStoreCodec.encodeOverrides(overrides)

        assertTrue(json.contains("\"v\":${ProcessingModeStoreCodec.SCHEMA_VERSION}"))
        assertEquals(overrides, ProcessingModeStoreCodec.decodeOverrides(json))
    }

    @Test
    fun `legacy unversioned overrides map still decodes`() {
        val legacy = """{"proofread":"Legacy prompt","email":"Email prompt"}"""
        assertEquals(
            mapOf("proofread" to "Legacy prompt", "email" to "Email prompt"),
            ProcessingModeStoreCodec.decodeOverrides(legacy),
        )
    }

    @Test
    fun `null override values and empty keys are dropped`() {
        val json = """{"v":1,"overrides":{"proofread":null,"":"orphan","email":"kept"}}"""
        assertEquals(mapOf("email" to "kept"), ProcessingModeStoreCodec.decodeOverrides(json))
    }

    @Test
    fun `corrupt overrides json decodes to empty`() {
        assertEquals(emptyMap<String, String>(), ProcessingModeStoreCodec.decodeOverrides("not json {"))
        assertEquals(emptyMap<String, String>(), ProcessingModeStoreCodec.decodeOverrides(""))
        assertEquals(emptyMap<String, String>(), ProcessingModeStoreCodec.decodeOverrides("[1,2,3]"))
    }

    @Test
    fun `versioned envelope without payload decodes to empty overrides`() {
        assertEquals(emptyMap<String, String>(), ProcessingModeStoreCodec.decodeOverrides("""{"v":1}"""))
    }

    // endregion

    // region presets

    @Test
    fun `presets round-trip through the versioned envelope`() {
        val json = ProcessingModeStoreCodec.encodePresets(listOf(preset))

        assertTrue(json.contains("\"v\":${ProcessingModeStoreCodec.SCHEMA_VERSION}"))
        assertEquals(listOf(preset), ProcessingModeStoreCodec.decodePresets(json))
    }

    @Test
    fun `legacy unversioned preset array still decodes`() {
        val legacy =
            """
            [{"id":"user_9","name":"Old","prompt":"Old prompt","originalPrompt":"Old original"}]
            """.trimIndent()
        assertEquals(
            listOf(
                StoredCustomPreset(
                    id = "user_9",
                    name = "Old",
                    prompt = "Old prompt",
                    originalPrompt = "Old original",
                ),
            ),
            ProcessingModeStoreCodec.decodePresets(legacy),
        )
    }

    @Test
    fun `preset with a missing required field is dropped instead of carrying a null`() {
        // Gson would happily materialize name = null inside a non-null String without this
        // validation; the corruption used to surface later as a "null" label in the UI.
        val json =
            """
            {"v":1,"presets":[
              {"id":"user_1","prompt":"No name","originalPrompt":"No name"},
              {"id":"user_2","name":"Valid","prompt":"Valid prompt","originalPrompt":"Valid prompt"}
            ]}
            """.trimIndent()
        val decoded = ProcessingModeStoreCodec.decodePresets(json)
        assertEquals(1, decoded.size)
        assertEquals("user_2", decoded.single().id)
    }

    @Test
    fun `missing originalPrompt falls back to the current prompt`() {
        val json = """{"v":1,"presets":[{"id":"user_3","name":"N","prompt":"P"}]}"""
        val decoded = ProcessingModeStoreCodec.decodePresets(json)
        assertEquals("P", decoded.single().originalPrompt)
    }

    @Test
    fun `null entries and blank ids are dropped`() {
        val json =
            """
            {"v":1,"presets":[null,{"id":" ","name":"N","prompt":"P","originalPrompt":"P"}]}
            """.trimIndent()
        assertEquals(emptyList<StoredCustomPreset>(), ProcessingModeStoreCodec.decodePresets(json))
    }

    @Test
    fun `corrupt presets json decodes to empty`() {
        assertEquals(emptyList<StoredCustomPreset>(), ProcessingModeStoreCodec.decodePresets("{{{{"))
        assertEquals(emptyList<StoredCustomPreset>(), ProcessingModeStoreCodec.decodePresets(""))
        assertEquals(emptyList<StoredCustomPreset>(), ProcessingModeStoreCodec.decodePresets("\"just a string\""))
    }

    @Test
    fun `future schema version is read best-effort instead of wiping user data`() {
        val json =
            """
            {"v":99,"presets":[{"id":"user_7","name":"Future","prompt":"FP","originalPrompt":"FO"}]}
            """.trimIndent()
        val decoded = ProcessingModeStoreCodec.decodePresets(json)
        assertEquals(1, decoded.size)
        assertEquals("Future", decoded.single().name)
    }

    // endregion
}
