package dev.chirpboard.app.feature.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionRecoveryAffordanceUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingRecoveryAffordance_isVisible() {
        composeRule.setContent {
            MaterialTheme {
                PendingRecoveryAffordance(
                    diagnostics = RecoveryDiagnosticsUi(),
                    actionsEnabled = true,
                    onRecoverPending = {},
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptionRecoveryTestTags.PendingRecoverButton).assertIsDisplayed()
        composeRule.onNodeWithText("Recover Queue").assertIsDisplayed()
        composeRule.onAllNodesWithTag(TranscriptionRecoveryTestTags.EnhancingRecoverButton).assertCountEquals(0)
        composeRule.onAllNodesWithTag(TranscriptionRecoveryTestTags.EnhancingRetranscribeButton).assertCountEquals(0)
    }

    @Test
    fun enhancingRecoveryAffordances_showRecoverAndRetranscribe() {
        composeRule.setContent {
            MaterialTheme {
                Row {
                    EnhancingRecoveryActions(
                        actionsEnabled = true,
                        onRecoverEnhancing = {},
                        onRetranscribe = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(TranscriptionRecoveryTestTags.EnhancingRecoverButton).assertIsDisplayed()
        composeRule.onNodeWithTag(TranscriptionRecoveryTestTags.EnhancingRetranscribeButton).assertIsDisplayed()
        composeRule.onNodeWithText("Recover").assertIsDisplayed()
        composeRule.onNodeWithText("Re-transcribe").assertIsDisplayed()
        composeRule.onAllNodesWithTag(TranscriptionRecoveryTestTags.PendingRecoverButton).assertCountEquals(0)
    }
}
