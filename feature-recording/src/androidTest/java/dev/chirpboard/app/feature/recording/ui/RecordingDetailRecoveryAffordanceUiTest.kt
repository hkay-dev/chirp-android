package dev.chirpboard.app.feature.recording.ui

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
class RecordingDetailRecoveryAffordanceUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingRecoveryAffordance_isVisible() {
        composeRule.setContent {
            MaterialTheme {
                PendingRecoveryAffordance(
                    diagnostics = RecoveryDiagnosticsUi(),
                    actionsEnabled = true,
                    onRecoverPending = {}
                )
            }
        }

        composeRule.onNodeWithTag(RecordingDetailRecoveryTestTags.PendingRecoverButton).assertIsDisplayed()
        composeRule.onNodeWithText("Recover Queue").assertIsDisplayed()
        composeRule.onAllNodesWithTag(RecordingDetailRecoveryTestTags.EnhancingRecoverButton).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RecordingDetailRecoveryTestTags.EnhancingRetranscribeButton).assertCountEquals(0)
    }

    @Test
    fun enhancingRecoveryAffordances_showRecoverAndRetranscribe() {
        composeRule.setContent {
            MaterialTheme {
                Row {
                    EnhancingRecoveryActions(
                        actionsEnabled = true,
                        onRecoverEnhancing = {},
                        onRetranscribe = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RecordingDetailRecoveryTestTags.EnhancingRecoverButton).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingDetailRecoveryTestTags.EnhancingRetranscribeButton).assertIsDisplayed()
        composeRule.onNodeWithText("Recover").assertIsDisplayed()
        composeRule.onNodeWithText("Re-transcribe").assertIsDisplayed()
        composeRule.onAllNodesWithTag(RecordingDetailRecoveryTestTags.PendingRecoverButton).assertCountEquals(0)
    }
}
