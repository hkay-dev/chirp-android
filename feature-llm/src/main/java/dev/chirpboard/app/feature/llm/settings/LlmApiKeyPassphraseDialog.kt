package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.chirpboard.app.feature.llm.R

enum class LlmPassphraseDialogMode {
    Backup,
    Restore,
}

@Composable
internal fun LlmApiKeyPassphraseDialog(
    mode: LlmPassphraseDialogMode,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String) -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable { mutableStateOf("") }
    val requiresConfirmation = mode == LlmPassphraseDialogMode.Backup
    val canConfirm =
        passphrase.length >= MIN_PASSPHRASE_LENGTH &&
            (!requiresConfirmation || passphrase == confirmPassphrase)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    when (mode) {
                        LlmPassphraseDialogMode.Backup -> stringResource(R.string.llm_backup_passphrase_title)
                        LlmPassphraseDialogMode.Restore -> stringResource(R.string.llm_restore_passphrase_title)
                    },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text =
                        when (mode) {
                            LlmPassphraseDialogMode.Backup -> stringResource(R.string.llm_backup_passphrase_body)
                            LlmPassphraseDialogMode.Restore -> stringResource(R.string.llm_restore_passphrase_body)
                        },
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.llm_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (requiresConfirmation) {
                    OutlinedTextField(
                        value = confirmPassphrase,
                        onValueChange = { confirmPassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.llm_passphrase_confirm_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            if (confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase) {
                                Text(stringResource(R.string.llm_passphrase_mismatch))
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.llm_passphrase_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.llm_passphrase_cancel))
            }
        },
    )
}

internal const val MIN_PASSPHRASE_LENGTH = 8
