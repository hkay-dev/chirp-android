package dev.chirpboard.app.feature.recording.ui.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.entity.Profile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun profileCard_showsName_andFriendlyProcessingModeLabel() {
        // REC-1: the profile name lives in the headline. REC-2: the processing-mode chip shows the
        // localized label ("Meeting Notes"), never the raw persisted id ("meeting_notes").
        val profile =
            Profile(
                name = "Standups",
                defaultProcessingMode = "meeting_notes",
                autoTranscribe = false,
                autoTitle = false,
                autoSummary = false,
                autoExportToObsidian = false,
            )

        composeRule.setContent {
            MaterialTheme {
                ProfileCard(
                    profileItem = ProfileItemState(profile),
                    onClick = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Standups").assertIsDisplayed()
        composeRule.onNodeWithText("Meeting Notes").assertIsDisplayed()
        composeRule.onNodeWithText("meeting_notes").assertDoesNotExist()
    }

    @Test
    fun profileCard_withNoProcessingMode_showsNoModeChip() {
        // REC-2: a null processing mode must not render the "None" chip.
        val profile =
            Profile(
                name = "Voice memo",
                defaultProcessingMode = null,
                autoTranscribe = false,
                autoTitle = false,
                autoSummary = false,
                autoExportToObsidian = false,
            )

        composeRule.setContent {
            MaterialTheme {
                ProfileCard(
                    profileItem = ProfileItemState(profile),
                    onClick = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Voice memo").assertIsDisplayed()
        composeRule.onNodeWithText("None (No processing)").assertDoesNotExist()
    }

    @Test
    fun profileCard_withManyFeatures_collapsesExtrasIntoAccessibleOverflowChip() {
        // PROP-10: five feature chips would push the card height past two lines at narrow widths.
        // Only the first three render as chips; the rest collapse into a single "+N" overflow chip
        // whose contentDescription names the hidden features for TalkBack.
        val profile =
            Profile(
                name = "Everything",
                defaultProcessingMode = "meeting_notes",
                autoTranscribe = true,
                autoTitle = true,
                autoSummary = true,
                autoExportToObsidian = true,
            )

        composeRule.setContent {
            MaterialTheme {
                ProfileCard(
                    profileItem = ProfileItemState(profile),
                    onClick = {},
                    onDelete = {},
                )
            }
        }

        // First three chips are visible.
        composeRule.onNodeWithText("Transcribe").assertIsDisplayed()
        composeRule.onNodeWithText("Auto Title").assertIsDisplayed()
        composeRule.onNodeWithText("Auto Summary").assertIsDisplayed()
        // The remaining two are hidden behind a "+2" overflow chip.
        composeRule.onNodeWithText("+2").assertIsDisplayed()
        composeRule.onNodeWithText("Obsidian").assertDoesNotExist()
        // The overflow chip's contentDescription names the hidden features.
        composeRule.onNodeWithContentDescription("Obsidian, Meeting Notes").assertIsDisplayed()
    }
}
