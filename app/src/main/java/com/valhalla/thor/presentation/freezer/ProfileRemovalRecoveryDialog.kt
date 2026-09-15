// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

@Composable
internal fun ProfileRemovalRecoveryDialog(
    recovery: ProfileRemovalRecovery,
    isWorking: Boolean,
    onUnfreeze: () -> Unit,
    onKeepFrozen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        modifier = Modifier.testTag("profile_removal_recovery"),
        title = { Text(stringResource(R.string.profile_removal_recovery_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.profile_removal_recovery_message, recovery.apps.size))
                recovery.apps.forEach { candidate ->
                    Column {
                        Text(candidate.app?.appName ?: candidate.packageName)
                        if (candidate.app == null) {
                            Text(
                                stringResource(R.string.profile_removal_state_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                recovery.error?.let { error ->
                    Text(error.asString(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onUnfreeze,
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth().testTag("profile_removal_unfreeze"),
                ) { Text(stringResource(R.string.profile_removal_unfreeze_continue)) }
                OutlinedButton(
                    onClick = onKeepFrozen,
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth().testTag("profile_removal_keep"),
                ) { Text(stringResource(R.string.profile_removal_keep_frozen)) }
                TextButton(
                    onClick = onDismiss,
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth().testTag("profile_removal_cancel"),
                ) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
