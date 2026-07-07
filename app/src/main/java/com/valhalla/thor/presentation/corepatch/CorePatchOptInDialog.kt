package com.valhalla.thor.presentation.corepatch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

/**
 * Type-to-confirm master opt-in for CorePatch (Xposed signature-bypass).
 *
 * Deliberately blunt: it spells out that the user is disabling Android's tamper/impersonation
 * protection and that Thor is not responsible for the fallout. The confirm button is enabled ONLY
 * when the typed phrase passes [confirmPhraseMatches] — a plain OK tap can't slip through.
 *
 * On confirm the caller (SettingsViewModel) flips the durable `corePatchEnabled` preference on;
 * this composable owns no persistence itself.
 */
@Composable
fun CorePatchOptInDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val canConfirm = confirmPhraseMatches(input)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.core_patch_optin_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.core_patch_optin_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        R.string.core_patch_optin_type_instruction,
                        CORE_PATCH_CONFIRM_PHRASE
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    isError = input.isNotEmpty() && !canConfirm,
                    label = { Text(stringResource(R.string.core_patch_optin_field_label)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = canConfirm
            ) {
                Text(
                    text = stringResource(R.string.core_patch_optin_enable),
                    color = if (canConfirm) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
