// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
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
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.SizeLabelKind
import com.valhalla.thor.domain.model.labelKind
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.settings.PassphraseError
import com.valhalla.thor.presentation.settings.passphraseErrorText
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The user-facing name of a storage class.
 *
 * `R` lives here rather than on [DataClass] — a `@StringRes` on a `domain/model` enum would put an
 * Android type in the layer that is defined by not having any.
 */
// `internal`, not `private`: the restore screen names the same four classes, and a second `when` over
// `DataClass` is a second place for the mapping to drift. Kept in this file because this is where it
// was written and moving it would touch a green one for no gain.
@StringRes
internal fun dataClassLabel(dataClass: DataClass): Int = when (dataClass) {
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

    // Resolved here rather than in the callback: a result callback is not a composable scope.
    val notPersistable = stringResource(R.string.backup_destination_not_persistable)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // A document provider is free to return a tree Uri without offering a persistable grant,
            // and this throws SecurityException when it does — from inside a result callback, so an
            // uncaught one takes the process with it. Not hypothetical on OEM ROMs with their own
            // file providers. The folder is only recorded when the grant is real: a Uri Thor cannot
            // re-open after a restart is a destination that breaks later instead of now.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onSuccess {
                // The same preference the export flow writes: one chosen folder, not two.
                scope.launch {
                    preferenceRepository.setExportDirUri(uri.toString())
                    // After the write, never beside it: `currentTargetLabel()` resolves against the
                    // saved Uri at call time, so asking first would answer with the old folder — and
                    // the sheet would name the folder the archive is not going to.
                    viewModel.refreshDestination()
                }
            }.onFailure {
                Toast.makeText(context, notPersistable, Toast.LENGTH_LONG).show()
            }
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

                    // Beside the destination, because the destination is what it is about: the file
                    // name is derived from the app and its version code, so a second backup of the
                    // same version lands on the first one. What that does depends on which of the
                    // three backends is answering — `renameTo` on legacy Downloads replaces it
                    // outright — and the user was previously told none of it.
                    Text(
                        text = stringResource(R.string.backup_overwrite_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.selected.isEmpty()) {
                        // The other half of C1's fix. `canStart` now refuses an empty selection, and a
                        // button that greys out for a reason the sheet does not give is the defect
                        // this feature already had once (see the passphrase field below). The user
                        // ticking only *Include the app installer* has made a deliberate choice and
                        // needs to be told why it is not one Thor can honour.
                        Text(
                            text = stringResource(R.string.backup_nothing_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // The rule itself lives in the view model, next to `canStart`, so it is pinned by
                    // JVM tests on both sides of the minimum rather than by nothing. This file used to
                    // hold a second copy of it, written in the opposite direction (`length >= MIN`),
                    // which is how the sheet came to enforce a rule it never explained.
                    val refusal = backupPassphraseRefusal(
                        needed = state.passphraseNeeded,
                        passphrase = passphrase,
                        confirmation = confirmation,
                    )
                    // What to *show* is a narrower question than what to refuse: an empty field is too
                    // short, and telling a user their passphrase is too short before they have typed a
                    // character is nagging, not help. So the refusal still greys the button from the
                    // first frame; the sentence appears once there is something to say it about. The
                    // pre-existing `isError` on the confirmation field used the same rule.
                    val shownRefusal = refusal?.takeIf {
                        when (it) {
                            PassphraseError.TOO_SHORT -> passphrase.isNotEmpty()
                            PassphraseError.MISMATCH -> confirmation.isNotEmpty()
                            // Not produced by `backupPassphraseRefusal` — it is the settings sheet's
                            // report of a vault write that failed, which is not a refusal of anything
                            // typed here. Enumerated rather than caught by an `else` so that adding a
                            // fourth error breaks this `when` instead of silently hiding it.
                            PassphraseError.STORE_FAILED -> false
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
                            isError = shownRefusal == PassphraseError.TOO_SHORT,
                            supportingText = shownRefusal
                                ?.takeIf { it == PassphraseError.TOO_SHORT }
                                ?.let { { Text(passphraseErrorText(it)) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.running,
                            isError = shownRefusal == PassphraseError.MISMATCH,
                            // The mismatch was already marked with `isError` and nothing else; the
                            // supporting text is what turns a red outline into a reason.
                            supportingText = shownRefusal
                                ?.takeIf { it == PassphraseError.MISMATCH }
                                ?.let { { Text(passphraseErrorText(it)) } },
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
                        if (state.queued) {
                            // Says where the job is, and stops there. Every job is appended to one
                            // chain, and a dependent whose prerequisite fails is cancelled — so
                            // "starting soon" would be a promise WorkManager has not made.
                            Text(
                                text = stringResource(R.string.backup_queued),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

                    Button(
                        onClick = {
                            viewModel.beginBackup(passphrase.toCharArray(), rememberIt)
                        },
                        enabled = state.canStart && refusal == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.backup_start))
                    }

                    state.finished?.let { finish ->
                        BackupOutcome(finish = finish, onDismiss = viewModel::dismissResult)
                    }
                }
            }
        }
    }
}

/**
 * How a finished backup is reported.
 *
 * Three outcomes, five sentences, split on [BackupFinish.workerRan] — the same shape as
 * `RestoreOutcome` in `ArchiveRestoreScreen`, because the same three things can happen and the user
 * needs to be able to tell them apart. Previously all three rendered as *"Backup failed: it stopped
 * without saying why"*, which was the honest sentence for exactly one of them.
 *
 * | Outcome | Copy |
 * |---|---|
 * | `Succeeded` | it was saved |
 * | `Failed(workerRan = true)` | the worker's reason, then *no file was saved and your data is untouched* |
 * | `Failed(workerRan = false)` | the reason, then *nothing was started* |
 * | `Cancelled(workerRan = false)` | the chain cancelled it; nothing was saved |
 * | `Cancelled(workerRan = true)` | it was cancelled after it started, then the same *nothing saved* line |
 *
 * The second sentence is **not** `restore_failed_partial`'s counterpart in meaning, only in position.
 * A restore that stops part-way leaves the app's data half-written, so its damage sentence tells the
 * user to go and look; a backup that stops part-way leaves the app alone — it only ever read it — and
 * publishes nothing, because `AppArchiveStoreImpl` writes to a `.part` name and renames on success
 * only. Copying restore's sentence across would have invented damage that cannot happen here, which is
 * the same class of error in the opposite direction.
 */
@Composable
private fun BackupOutcome(finish: BackupFinish, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (finish) {
            BackupFinish.Succeeded -> Text(
                text = stringResource(R.string.backup_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            is BackupFinish.Failed -> {
                Text(
                    text = stringResource(
                        R.string.backup_failed,
                        finish.reason ?: stringResource(R.string.backup_failed_unknown)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(
                        if (finish.workerRan) {
                            R.string.backup_failed_nothing_saved
                        } else {
                            // The enqueue returned no id, or the block around it threw. Both are
                            // decided before a job exists.
                            R.string.backup_failed_not_started
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            is BackupFinish.Cancelled -> if (finish.workerRan) {
                Text(
                    text = stringResource(R.string.backup_cancelled_after_start),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.backup_failed_nothing_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                // The common cancel: a dependent of a failed job, cancelled before `doWork`. Its own
                // string, ending in "Try again", because there is nothing wrong with the request —
                // only with the job that happened to be ahead of it.
                Text(
                    text = stringResource(R.string.backup_cancelled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        // The dismiss `dismissResult()` was written for and never given. Without it a *success* message
        // sat under a still-enabled Back up button for as long as the sheet stayed open, and the only
        // way to clear it was to start another backup.
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.backup_outcome_dismiss))
        }
    }
}

/**
 * What a class's size may be rendered as.
 *
 * `Formatter.formatShortFileSize` is what every other size in Thor goes through — the export sheet,
 * the app detail body, the bundle builder — so this reads in the same units the rest of the app uses.
 */
// `internal` so the restore screen shares it rather than copying it. The copy is the danger: this
// function carries the "never render Undetermined as 0 B" rule, and a second implementation is a
// second place for that rule to rot.
@Composable
internal fun sizeLabel(size: DataClassSize?): String = when (val kind = size?.labelKind()) {
    // Null is "not measured yet", which is not the same claim as Unknown ("measured, and failed").
    null -> stringResource(R.string.backup_size_measuring)
    is SizeLabelKind.Bytes -> Formatter.formatShortFileSize(LocalContext.current, kind.value)
    SizeLabelKind.Empty -> stringResource(R.string.backup_size_empty)
    // Never "0 B". §10.
    SizeLabelKind.Unknown -> stringResource(R.string.backup_size_unknown)
}

@Composable
internal fun CheckRow(
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
