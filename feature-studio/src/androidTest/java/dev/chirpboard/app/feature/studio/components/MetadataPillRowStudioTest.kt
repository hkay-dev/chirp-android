package dev.chirpboard.app.feature.studio.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.core.ui.components.MetadataPillRow
import dev.chirpboard.app.data.model.RecordingSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataPillRowStudioTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun metadataPillRow_rendersDurationAndSource() {
        composeRule.setContent {
            MaterialTheme {
                MetadataPillRow(
                    createdAtMs = System.currentTimeMillis(),
                    durationMs = 65_000L,
                    source = RecordingSource.APP,
                )
            }
        }

        composeRule.onNodeWithText("1:05").assertIsDisplayed()
        composeRule.onNodeWithText("App").assertIsDisplayed()
    }

    @Test
    fun metadataPillRow_rendersImportedSource() {
        composeRule.setContent {
            MaterialTheme {
                MetadataPillRow(
                    createdAtMs = System.currentTimeMillis(),
                    durationMs = 3_600_000L,
                    source = RecordingSource.IMPORTED,
                )
            }
        }

        composeRule.onNodeWithText("1:00:00").assertIsDisplayed()
        composeRule.onNodeWithText("Imported").assertIsDisplayed()
    }
}
