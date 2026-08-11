// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.SizeLabelKind
import com.valhalla.thor.domain.model.labelKind
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The user-facing name of a storage class.
 *
 * `R` lives here rather than on [DataClass] — a `@StringRes` on a `domain/model` enum would put an
 * Android type in the layer that is defined by not having any.
 */
@StringRes
private fun dataClassLabel(dataClass: DataClass): Int = when (dataClass) {
    DataClass.CE -> R.string.backup_class_ce
    DataClass.DE -> R.string.backup_class_de
    DataClass.EXTERNAL_DATA -> R.string.backup_class_external_data
    DataClass.EXTERNAL_MEDIA -> R.string.backup_class_external_media
}

/**
 * §10's backup sheet: pick what to include, pick a passphrase, watch it run.
 *
 * Self-contained in the same way [com.valhalla.thor.presentation.appList.ExportBottomSheet] is — its
 * own [ModalBottomSheet], its own SAF picker, its dependencies through Koin rather than through
 * parameters — so a host only has to decide whether to show it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackupSheet(packageName: String, appLabel: String, onDismiss: () -> Unit) {
    // Scoped to this composable, matching what AppInfoSheet does for AppInfoDetailsViewModel. The
    // default owner is the host Activity or nav entry, whose store outlives the sheet: the next app's
    // sheet would then get this app's view model back, and `start()`'s idempotence guard — which
    // exists so a recomposition does not re-run `du` — would suppress the reload and show the wrong
    // app's sizes. Losing the view model on dismiss costs nothing, because a job that is still
    // running is found again through `runningJobFor` when the sheet is next opened.
    val viewModel = koinViewModel<AppBackupViewModel>(
        viewModelStoreOwner = rememberViewModelStoreOwner()
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(packageName) { viewModel.start(packageName, appLabel) }

    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var rememberIt by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // The same preference the export flow writes: one chosen folder, not two.
            scope.launch { preferenceRepository.setExportDirUri(uri.toString()) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                text = stringResource(R.string.action_backup).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = state.appLabel.ifBlank { packageName },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            when (state.supported) {
                // Still probing. A spinner, never the refusal panel — see AppBackupUiState.supported.
                null -> CircularProgressIndicator()

                false -> Text(
                    text = stringResource(R.string.backup_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                true -> {
                    CheckRow(
                        checked = state.includeBundle,
                        enabled = !state.running,
                        label = stringResource(R.string.backup_include_bundle),
                        detail = stringResource(R.string.backup_include_bundle_desc),
                        onCheckedChange = viewModel::setIncludeBundle
                    )

                    DataClass.entries.forEach { dataClass ->
                        CheckRow(
                            checked = dataClass in state.selected,
                            enabled = !state.running,
                            label = stringResource(dataClassLabel(dataClass)),
                            detail = sizeLabel(state.sizes[dataClass]),
                            onCheckedChange = { viewModel.toggleClass(dataClass) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.destinationLabel?.let {
                                stringResource(R.string.backup_destination, it)
                            } ?: stringResource(R.string.backup_destination_pending),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { picker.launch(null) }, enabled = !state.running) {
                            Text(stringResource(R.string.backup_change_destination))
                        }
                    }

                    if (state.passphraseNeeded) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text(stringResource(R.string.backup_passphrase)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.running,
                            isError = confirmation.isNotEmpty() && confirmation != passphrase,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            // §5.4, stated rather than implied: Thor cannot recover this.
                            text = stringResource(R.string.backup_passphrase_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        CheckRow(
                            checked = rememberIt,
                            enabled = !state.running,
                            label = stringResource(R.string.backup_remember_passphrase),
                            detail = null,
                            onCheckedChange = { rememberIt = it }
                        )
                    } else {
                        TextButton(
                            onClick = viewModel::useDifferentPassphrase,
                            enabled = !state.running
                        ) {
                            Text(stringResource(R.string.backup_use_different_passphrase))
                        }
                    }

                    if (state.running) {
                        val percent = state.progress?.percent
                        if (percent == null) {
                            // Indeterminate, never a determinate bar pinned at 0 — see the view-model
                            // test that names this.
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        state.progress?.let {
                            Text(
                                text = it.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val passphraseUsable = !state.passphraseNeeded ||
                        (passphrase.length >= MIN_PASSPHRASE_LENGTH && passphrase == confirmation)

                    Button(
                        onClick = {
                            viewModel.beginBackup(passphrase.toCharArray(), rememberIt)
                        },
                        enabled = state.canStart && passphraseUsable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.backup_start))
                    }

                    state.finished?.let { finish ->
                        Text(
                            text = when (finish) {
                                BackupFinish.Succeeded -> stringResource(R.string.backup_done)
                                is BackupFinish.Failed -> stringResource(
                                    R.string.backup_failed,
                                    finish.reason ?: stringResource(R.string.backup_failed_unknown)
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (finish is BackupFinish.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a class's size may be rendered as.
 *
 * `Formatter.formatShortFileSize` is what every other size in Thor goes through — the export sheet,
 * the app detail body, the bundle builder — so this reads in the same units the rest of the app uses.
 */
@Composable
private fun sizeLabel(size: DataClassSize?): String = when (val kind = size?.labelKind()) {
    // Null is "not measured yet", which is not the same claim as Unknown ("measured, and failed").
    null -> stringResource(R.string.backup_size_measuring)
    is SizeLabelKind.Bytes -> Formatter.formatShortFileSize(LocalContext.current, kind.value)
    SizeLabelKind.Empty -> stringResource(R.string.backup_size_empty)
    // Never "0 B". §10.
    SizeLabelKind.Unknown -> stringResource(R.string.backup_size_unknown)
}

@Composable
private fun CheckRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    detail: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
