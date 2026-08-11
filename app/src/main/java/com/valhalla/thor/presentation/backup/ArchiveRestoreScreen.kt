// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreWarning
import com.valhalla.thor.domain.model.DataClassSize
import java.text.DateFormat
import java.util.Date
import org.koin.androidx.compose.koinViewModel

@StringRes
private fun refusalLabel(refusal: ArchiveRestoreRefusal): Int = when (refusal) {
    ArchiveRestoreRefusal.SIGNER_MISMATCH -> R.string.restore_refused_signer_mismatch
    ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE -> R.string.restore_refused_signer_unverifiable
    ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT -> R.string.restore_refused_app_absent
    ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE -> R.string.restore_refused_class_missing
    ArchiveRestoreRefusal.NOTHING_SELECTED -> R.string.restore_refused_nothing_selected
    ArchiveRestoreRefusal.SCHEMA_TOO_NEW -> R.string.restore_refused_schema_too_new
    ArchiveRestoreRefusal.INVALID_PACKAGE_NAME -> R.string.restore_refused_invalid_package_name
    ArchiveRestoreRefusal.INVALID_USER_ID -> R.string.restore_refused_invalid_user_id
}

@StringRes
private fun warningLabel(warning: ArchiveRestoreWarning): Int = when (warning) {
    ArchiveRestoreWarning.INSTALLED_VERSION_OLDER -> R.string.restore_warning_version_older
    ArchiveRestoreWarning.CE_WITHOUT_DE -> R.string.restore_warning_ce_without_de
}

/**
 * §10's restore entry point.
 *
 * @param uriString null when the user arrived from Settings and has yet to pick a file. Not defaulted:
 *   a defaulted parameter here would let a call site silently forget the VIEW-delivered URI, and the
 *   only symptom would be a screen that always asks for a file.
 */
@Composable
internal fun ArchiveRestoreScreen(uriString: String?, onBack: () -> Unit) {
    // Scoped to this composable for the same reason `AppBackupSheet` scopes its own: the default owner
    // outlives one visit, and this screen is reachable twice with two different archives — from
    // Settings with no URI, and from a VIEW intent with one. A view model held over from the previous
    // visit would answer with the previous archive's header. A restore already running is not lost
    // with it: `runningJobFor` re-attaches to it when the screen is opened again.
    val viewModel = koinViewModel<ArchiveRestoreViewModel>(
        viewModelStoreOwner = rememberViewModelStoreOwner()
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }

    LaunchedEffect(uriString) { uriString?.let(viewModel::open) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // No takePersistableUriPermission: OpenDocument's grant lasts for this task, which is all the
        // worker needs, and asking for persistence Thor never uses would be a permission held for
        // nothing.
        if (uri != null) viewModel.open(uri.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.restore_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        state.interrupted?.let { crumb ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    // §8.5, in the words the spec uses: the user is told the data may be incomplete
                    // rather than discovering it when the app crashes.
                    text = stringResource(R.string.restore_interrupted, crumb.appLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                TextButton(onClick = viewModel::acknowledgeInterruption) {
                    Text(stringResource(R.string.restore_interrupted_dismiss))
                }
            }
        }

        if (state.supported == false) {
            Text(
                text = stringResource(R.string.backup_unsupported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (state.loading) CircularProgressIndicator()

        val header = state.header
        if (header == null) {
            Text(
                text = stringResource(R.string.restore_pick_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // */* rather than THORBAK_MIME: providers report a .thorbak as octet-stream, zip or
            // nothing at all depending on which one is answering, and a narrow filter greys out the
            // file the user is looking straight at.
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.restore_pick_file))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = header.packageName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.restore_archive_version,
                        header.versionName ?: "?",
                        header.versionCode
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.restore_archive_created,
                        DateFormat.getDateTimeInstance().format(Date(header.createdAt))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.fileName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.installFirst) {
                Text(
                    // §8.1 is explicit that this is not a refusal, so it is stated as a plan rather
                    // than as a problem.
                    text = stringResource(R.string.restore_will_install_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            header.heldClasses().forEach { dataClass ->
                // `heldClasses()` is defined as the classes with a member, so this cannot drop a row
                // the user should have seen; it is here so the size below is read off a member that
                // exists rather than defaulted to a number nothing measured.
                val member = header.member(dataClass) ?: return@forEach
                CheckRow(
                    checked = dataClass in state.selected,
                    enabled = !state.running,
                    label = stringResource(dataClassLabel(dataClass)),
                    // `Known`, and only `Known`: an archive records the byte count it actually packed,
                    // so there is no tri-state to render here. Routed through `sizeLabel` anyway so
                    // every size in this feature is formatted by one function.
                    detail = sizeLabel(DataClassSize.Known(member.plainBytes)),
                    onCheckedChange = { viewModel.toggleClass(dataClass) }
                )
            }

            if (state.obbOffered) {
                // pluralStringResource, not stringResource — this is R.plurals, and the count is
                // passed twice on purpose: once to pick the quantity, once to fill %1$d.
                val obbCount = header.appBundle?.obbCount ?: 0
                CheckRow(
                    checked = state.restoreObb,
                    enabled = !state.running,
                    label = pluralStringResource(R.plurals.restore_include_obb, obbCount, obbCount),
                    detail = null,
                    onCheckedChange = viewModel::setRestoreObb
                )
            }

            state.refusal?.let {
                Text(
                    text = stringResource(refusalLabel(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.warnings.forEach {
                Text(
                    text = stringResource(warningLabel(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (state.refusal == null) {
                if (state.passphraseNeeded) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = state.passphraseError != null,
                        supportingText = state.passphraseError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            viewModel.submitPassphrase(passphrase.toCharArray())
                            // Dropped as soon as it is handed over. A String cannot be zeroed, so the
                            // most this can do is stop holding a live reference to one — which is
                            // still the difference between a passphrase that survives until GC and
                            // one that survives until the screen closes.
                            passphrase = ""
                        },
                        enabled = passphrase.isNotEmpty() && !state.running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.restore_unlock))
                    }
                } else if (state.unlocked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.restore_unlocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = viewModel::useDifferentPassphrase,
                            enabled = !state.running
                        ) {
                            Text(stringResource(R.string.backup_use_different_passphrase))
                        }
                    }
                }

                CheckRow(
                    checked = state.confirmed,
                    enabled = !state.running,
                    // "Replaces", not "restores". A merge is what a user assumes, and it is not what
                    // happens: whatever the app holds now for a selected class is deleted.
                    label = stringResource(R.string.restore_confirm_replace),
                    detail = null,
                    onCheckedChange = viewModel::setConfirmed
                )

                if (state.running) {
                    val percent = state.progress?.percent
                    if (percent == null) {
                        // Indeterminate, never a determinate bar pinned at 0 — a bar at 0 % that is
                        // moving is a different claim from one that has not started.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (state.queued) {
                        // Says where the job is, and stops there. Every job is appended to one chain,
                        // and a dependent whose prerequisite fails is cancelled before `doWork` runs —
                        // so "starting soon" would be a promise WorkManager has not made.
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
                    onClick = viewModel::beginRestore,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.restore_start))
                }
            }

            state.finished?.let { finish ->
                RestoreOutcome(finish = finish, onDismiss = viewModel::dismissResult)
            }

            if (!state.running) {
                // The way back out of a file that turned out to be the wrong one. Without it the
                // "choose a file" button is gone for good the moment a header loads, and a user who
                // picked the wrong backup has to leave the screen to pick another.
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.restore_pick_another))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.restore_back))
        }
    }
}

/**
 * How a finished restore is reported.
 *
 * Three outcomes, three sentences. The one thing none of them may do is describe a state the code
 * cannot vouch for: a failed restore has already deleted whatever it got through, and on the
 * install-first path it may have installed the app before failing — so the failure copy points at the
 * device rather than reassuring anyone that nothing happened.
 */
@Composable
private fun RestoreOutcome(finish: RestoreFinish, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (finish) {
            // §8.6: the honest instruction is "open it and check", because no amount of shell exit
            // codes proves the app is happy with what it was handed.
            is RestoreFinish.Succeeded -> {
                Text(
                    text = stringResource(R.string.restore_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (finish.warnings.isNotEmpty()) {
                    // The data landed, so this is not a failure — but a run that could not place the
                    // game data, or could not write §8.5's breadcrumb, has something to say and this
                    // is the only place it is ever said. The sentences are the worker's own.
                    Text(
                        text = stringResource(R.string.restore_done_warnings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    finish.warnings.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            is RestoreFinish.Failed -> {
                Text(
                    text = stringResource(
                        R.string.restore_failed,
                        finish.reason ?: stringResource(R.string.backup_failed_unknown)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.restore_failed_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // The only case in which "nothing was changed" is true, and it is true because `doWork`
            // was never called at all.
            RestoreFinish.Cancelled -> Text(
                text = stringResource(R.string.restore_cancelled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.restore_interrupted_dismiss))
        }
    }
}
