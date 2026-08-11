// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import org.koin.androidx.compose.koinViewModel

/** §5.4. The one place a stored passphrase can be replaced or removed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphraseSettingsSheet(onDismiss: () -> Unit) {
    // The default store owner is correct here, unlike in AppBackupSheet and AppInfoSheet. Those two
    // are opened against a package name, so the owner outliving the sheet would hand the next app's
    // sheet the previous app's view model; this sheet has no subject — there is one vault per device —
    // so an instance surviving a dismiss can only be right. What must not survive is the *outcome* of
    // a visit, and `dismiss()` below clears exactly that; the two text fields are `remember`ed in this
    // composition, which the host drops when it stops composing the sheet.
    val viewModel = koinViewModel<PassphraseSettingsViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = {
            // Clear the outcome as the sheet goes, or reopening it shows the last visit's "Saved".
            viewModel.dismiss()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.passphrase_settings_title).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            // §5.4's two consequences, stated where the choice is made rather than after it. Both are
            // properties of the format, so neither can be softened by a later UI change.
            Text(
                text = stringResource(R.string.passphrase_settings_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(R.string.passphrase_settings_no_reencrypt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.remembered) {
                Text(
                    text = stringResource(R.string.passphrase_settings_stored),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.backup_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )

            state.error?.let { error ->
                Text(
                    text = passphraseErrorText(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.saved) {
                Text(
                    text = stringResource(R.string.passphrase_settings_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        // Fresh arrays on every click, and the view model owns each one from here: it
                        // wipes both on every path it can take. The `String`s they were made from stay
                        // in this composition until the sheet leaves it, which is why the wipe there is
                        // described as narrowing the window rather than closing it.
                        viewModel.save(passphrase.toCharArray(), confirmation.toCharArray())
                    },
                    enabled = !state.busy && passphrase.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.passphrase_settings_save))
                }
                if (state.remembered) {
                    TextButton(
                        onClick = {
                            viewModel.forget()
                            passphrase = ""
                            confirmation = ""
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.passphrase_settings_forget))
                    }
                }
            }
        }
    }
}

/**
 * The enum-to-copy mapping, kept here rather than in the view model so the view model stays free of
 * `R` (and therefore JVM-testable).
 *
 * Each arm resolves its own string with exactly the arguments that string declares, rather than one
 * `stringResource(id, MIN_PASSPHRASE_LENGTH)` over a shared id. Only `passphrase_error_too_short`
 * carries a `%1$d`; handing the other two an argument they do not declare is legal at runtime and
 * silent at compile time, but it is the shape `StringFormatMatches` exists to flag, and `lint.xml`
 * must not be widened for it.
 */
@Composable
private fun passphraseErrorText(error: PassphraseError): String = when (error) {
    PassphraseError.TOO_SHORT ->
        stringResource(R.string.passphrase_error_too_short, MIN_PASSPHRASE_LENGTH)

    PassphraseError.MISMATCH -> stringResource(R.string.passphrase_error_mismatch)
    PassphraseError.STORE_FAILED -> stringResource(R.string.passphrase_error_store_failed)
}
