package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.chirpboard.app.feature.studio.StructuredOutcomeGroup
import dev.chirpboard.app.feature.studio.StructuredOutcomeItemUi
import dev.chirpboard.app.feature.studio.StructuredOutcomeSectionState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test

class SummaryTabTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun empty_state_shows_generate_prompt() {
        composeRule.setContent {
            MaterialTheme {
                SummaryTab(
                    summaryMarkdown = "",
                    structuredOutcomeSection = StructuredOutcomeSectionState(isVisible = true, hasTranscriptText = true),
                    onGenerateStructuredOutcomes = {},
                    onCopyStructuredOutcome = {},
                    onShareStructuredOutcome = {},
                    onAskAiAboutStructuredOutcome = {},
                )
            }
        }

        composeRule.onNodeWithText("Generate").assertIsDisplayed()
        composeRule.onNodeWithText("Generate tasks, decisions, and follow-ups from the latest transcript.").assertIsDisplayed()
    }

    @Test
    fun failed_state_without_snapshot_shows_retry_message() {
        composeRule.setContent {
            MaterialTheme {
                SummaryTab(
                    summaryMarkdown = "Short summary",
                    structuredOutcomeSection =
                        StructuredOutcomeSectionState(
                            isVisible = true,
                            hasTranscriptText = true,
                            failureMessage = "Schema parse failed",
                        ),
                    onGenerateStructuredOutcomes = {},
                    onCopyStructuredOutcome = {},
                    onShareStructuredOutcome = {},
                    onAskAiAboutStructuredOutcome = {},
                )
            }
        }

        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn’t generate structured outcomes. Schema parse failed").assertIsDisplayed()
    }

    @Test
    fun ready_stale_state_shows_cards_and_actions() {
        composeRule.setContent {
            MaterialTheme {
                SummaryTab(
                    summaryMarkdown = "Short summary",
                    structuredOutcomeSection =
                        StructuredOutcomeSectionState(
                            isVisible = true,
                            hasTranscriptText = true,
                            hasReadySnapshot = true,
                            hasReadyItems = true,
                            isStale = true,
                            tasks =
                                persistentListOf(
                                    StructuredOutcomeItemUi(
                                        id = "task-0",
                                        group = StructuredOutcomeGroup.TASKS,
                                        text = "Review the draft",
                                    ),
                                ),
                        ),
                    onGenerateStructuredOutcomes = {},
                    onCopyStructuredOutcome = {},
                    onShareStructuredOutcome = {},
                    onAskAiAboutStructuredOutcome = {},
                )
            }
        }

        composeRule
            .onNodeWithText(
                "These structured outcomes are out of date. Regenerate them from the latest transcript.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Review the draft").assertIsDisplayed()
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithText("Share").assertIsDisplayed()
        composeRule.onNodeWithText("Ask AI").assertIsDisplayed()
    }
}
