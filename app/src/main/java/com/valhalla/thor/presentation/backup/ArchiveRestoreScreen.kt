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
import androidx.compose.material3.AlertDialog
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
    ArchiveRestoreRefusal.INVALID_SCHEMA_VERSION -> R.string.restore_refused_invalid_schema_version
    ArchiveRestoreRefusal.INVALID_PACKAGE_NAME -> R.string.restore_refused_invalid_package_name
    ArchiveRestoreRefusal.INVALID_USER_ID -> R.string.restore_refused_invalid_user_id
}

@StringRes
private fun warningLabel(warning: ArchiveRestoreWarning): Int = when (warning) {
    ArchiveRestoreWarning.INSTALLED_VERSION_OLDER -> R.string.restore_warning_version_older
    ArchiveRestoreWarning.CE_WITHOUT_DE -> R.string.restore_warning_ce_without_de
}

/**
 * The `R` half of [ArchiveRestoreMessage], kept here beside [refusalLabel] and [warningLabel] so
 * `ArchiveRestoreViewModel` holds no Android resource ids and stays JVM-testable.
 *
 * Two arms, because the screen draws two kinds of sentence: one this feature wrote and translates, and
 * one produced below it that arrives already worded. The distinction is deliberate rather than a
 * fallback — see [ArchiveRestoreMessage].
 */
@Composable
private fun messageText(message: ArchiveRestoreMessage): String = when (message) {
    is ArchiveRestoreMessage.FromBelow -> message.text
    is ArchiveRestoreMessage.Known -> stringResource(reasonLabel(message.reason))
}

@StringRes
private fun reasonLabel(reason: ArchiveRestoreReason): Int = when (reason) {
    ArchiveRestoreReason.FILE_UNREADABLE -> R.string.restore_error_unreadable_file
    ArchiveRestoreReason.NOT_AN_ARCHIVE -> R.string.restore_error_not_an_archive
    ArchiveRestoreReason.WRONG_PASSPHRASE -> R.string.restore_error_wrong_passphrase
    ArchiveRestoreReason.UNLOCK_CHECK_FAILED -> R.string.restore_error_unlock_check_failed
    // The two that are interpolated into `restore_failed` ("Restore failed: %1$s") rather than drawn
    // on their own, which is why neither is capitalised or stopped.
    ArchiveRestoreReason.PASSPHRASE_LOST -> R.string.restore_failed_passphrase_lost
    ArchiveRestoreReason.SALT_UNREADABLE -> R.string.restore_failed_salt_unreadable
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
    // The default owner, which here is the `NavEntry` for `ThorRoute.ArchiveRestore` — `MainScreen`
    // installs `rememberViewModelStoreNavEntryDecorator()` on every back stack it builds, so the
    // nearest `LocalViewModelStoreOwner` at this call site is the entry, not the Activity. That store
    // is created when the route is pushed and cleared when it is popped, which is exactly how a visit
    // to this screen ends, and `ThorRoute.ArchiveRestore` carries `uriString` as part of its key, so
    // two archives are two keys, two entries and two stores. Both of the things a composition-scoped
    // owner would have been protecting against are therefore already impossible.
    //
    // This deliberately does **not** copy `AppBackupSheet`, whose `rememberViewModelStoreOwner()` is
    // correct for a reason this screen does not share: that sheet is a conditional composable inside
    // another entry's composition, at one call site reused for every app, so its default owner really
    // does outlive it and really would hand app B's sheet app A's view model. A screen that *is* an
    // entry does not meet that condition. Scoping here instead bought a risk — the owner keys on the
    // composite key hash, so a pane-count change (unfolding, resizing a split window) could plausibly
    // re-key it and reset the screen to "Choose a file", losing the parsed header, the unlocked
    // passphrase and the ticked confirmation.
    //
    // A restore already running survives either way: `runningJobFor` re-attaches to it.
    val viewModel = koinViewModel<ArchiveRestoreViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }

    LaunchedEffect(uriString) { uriString?.let(viewModel::open) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // No takePersistableUriPermission: OpenDocument's grant lasts for this task, which is all the
        // worker needs, and asking for persistence Thor never uses would be a permission held for
        // nothing.
        if (uri != null) {
            // The previous file's answers are not this file's. `open()` says exactly that and clears
            // every field it owns, but this one is composition state it cannot reach, so the prompt
            // came back pre-filled with the *previous* archive's text — masked, so indistinguishable
            // from something the user typed here — with Unlock already enabled, and spent a full
            // 210,000-iteration derivation to answer "wrong passphrase". Unconditional on purpose:
            // `open()` returns early for an unchanged URI, so re-picking the same file clears the
            // field too, which is the same "start this file again" the rest of that path means.
            passphrase = ""
            viewModel.open(uri.toString())
        }
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
                text = messageText(it),
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

            // Withdrawn once the job is live rather than drawn disabled, matching `AppBackupSheet`. A
            // running restore has already been told what to restore, so these rows can no longer change
            // anything — and a screenful of greyed checkboxes above the bar is a form the user reads,
            // cannot use, and has to scroll past to find the only thing that is moving.
            if (!state.running) {
                header.heldClasses().forEach { dataClass ->
                    // `heldClasses()` is defined as the classes with a member, so this cannot drop a row
                    // the user should have seen; it is here so the size below is read off a member that
                    // exists rather than defaulted to a number nothing measured.
                    val member = header.member(dataClass) ?: return@forEach
                    CheckRow(
                        checked = dataClass in state.selected,
                        enabled = !state.running,
                        label = stringResource(dataClassLabel(dataClass)),
                        // `Known`, and only `Known`: an archive records the byte count it actually
                        // packed, so there is no tri-state to render here. Routed through `sizeLabel`
                        // anyway so every size in this feature is formatted by one function.
                        detail = sizeLabel(DataClassSize.Known(member.plainBytes)),
                        onCheckedChange = { viewModel.toggleClass(dataClass) }
                    )
                }
            }

            if (state.obbOffered && !state.running) {
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

            // Where the rows above were, so the bar appears in the space the controls vacated instead
            // of below everything they left behind.
            if (state.running) RestoreRunning(state = state)

            // `!state.running` as well as an absent refusal: the passphrase field, the confirmation and
            // the Restore button are all decisions the job has already taken. The progress bar this
            // block used to hold has moved out of it — under a refusal it was unreachable anyway, which
            // is correct today only because a refused restore cannot be running.
            if (state.refusal == null && !state.running) {
                if (state.passphraseNeeded) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = state.passphraseError != null,
                        supportingText = state.passphraseError?.let { { Text(messageText(it)) } },
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
                        // `!state.loading` as well as `!state.running`: the derivation behind this
                        // button takes 210,000 PBKDF2 iterations, and the view model refuses a second
                        // submission while one is in flight. The button has to say so — a control that
                        // silently discards a tap is the thing that makes a user tap it again.
                        enabled = passphrase.isNotEmpty() && !state.running && !state.loading,
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

                Button(
                    onClick = viewModel::beginRestore,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.restore_start))
                }
            }

            state.finished?.let { finish ->
                RestoreOutcomeDialog(finish = finish, onDismiss = viewModel::dismissResult)
            }

            if (!state.running && state.finished !is RestoreFinish.Succeeded) {
                // The way back out of a file that turned out to be the wrong one. Without it the
                // "choose a file" button is gone for good the moment a header loads, and a user who
                // picked the wrong backup has to leave the screen to pick another.
                //
                // Withdrawn after a *successful* restore, which is the one moment the wrong-file case
                // cannot apply: the dialog over this column says "Restore finished. Open the app to
                // check it works", and offering "Choose a different file" behind it puts a destructive
                // operation one tap from an app that is now correct. Dismissing the outcome brings it
                // back, so nothing is lost — the user just has to acknowledge the result first.
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
 * What the screen shows while a restore is live: the bar, and what it is working on.
 *
 * No Background button, unlike `BackupRunning`'s sheet — this is a screen, so there is nothing to
 * dismiss, and the Back button at the bottom of the column is already the way out. Leaving is safe for
 * the same reason it is there: the job is a WorkManager foreground service with its own notification,
 * and `runningJobFor` re-attaches this screen to it on the way back in.
 */
@Composable
private fun RestoreRunning(state: ArchiveRestoreUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val percent = state.progress?.percent
        if (percent == null) {
            // Indeterminate, never a determinate bar pinned at 0 — a bar at 0 % that is moving is a
            // different claim from one that has not started.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.queued) {
            // Says where the job is, and stops there. Every job is appended to one chain, and a
            // dependent whose prerequisite fails is cancelled before `doWork` runs — so "starting soon"
            // would be a promise WorkManager has not made.
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
}

/**
 * [RestoreOutcome] in a dialog.
 *
 * A dialog rather than the card at the foot of the column it used to be, because of how tall the column
 * is: the title, the archive's three header lines, up to four class rows, the game-data row, the unlock
 * row, the confirmation row, the bar and the Restore button all sit above it. The sentence saying
 * whether an app's data came back therefore arrived below the fold, on the one screen where the user is
 * watching for exactly that — and a restore finishes minutes after the tap that started it, so it is
 * also the one event here the user is not already looking at the bottom of the screen for. It has to
 * interrupt rather than wait to be found.
 *
 * All three outcomes, not only the success that prompted this. A failure was equally invisible, and one
 * container for one decision is what keeps the five sentences [RestoreOutcome] documents from drifting
 * into two shapes.
 *
 * No auto-dismiss, deliberately — unlike the backup sheet's success frame, which closes itself after
 * three seconds. That one has nothing left to say; this one tells the user to go and open the app to
 * check it works, and may be carrying warnings under it.
 */
@Composable
private fun RestoreOutcomeDialog(finish: RestoreFinish, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { RestoreOutcome(finish = finish) },
        confirmButton = {
            // `restore_outcome_dismiss`, not `restore_interrupted_dismiss`: that one is named and
            // commented for the §8.5 breadcrumb banner at the top of this screen. Same word today, two
            // owners, so rewording the breadcrumb's button cannot silently reword this one.
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.restore_outcome_dismiss))
            }
        }
    )
}

/**
 * How a finished restore is reported.
 *
 * The body of [RestoreOutcomeDialog], which owns the dismiss button — so this stays a column of
 * sentences and nothing else.
 *
 * Three outcomes, five sentences: both failure arms split on [RestoreFinish.workerRan], because what
 * is honest depends on whether anything reached the device.
 *
 * | Outcome | Copy |
 * |---|---|
 * | `Succeeded` | it landed — go and check it, plus anything it finished in spite of |
 * | `Failed(workerRan = true)` | the worker's reason, then the damage sentence |
 * | `Failed(workerRan = false)` | the reason, then *nothing was started* |
 * | `Cancelled(workerRan = false)` | the chain cancelled it; nothing was changed |
 * | `Cancelled(workerRan = true)` | it was cancelled after it started, then the damage sentence |
 *
 * The one thing none of them may do is describe a state the code cannot vouch for — in either
 * direction. A restore that ran deletes each class before it writes it, and on the install-first path
 * it may have installed the app before failing, so *"nothing was changed"* is a lie there. A restore
 * refused before its enqueue touched nothing at all, so the damage sentence is a lie *here* — and a
 * worse one, because it ends by telling the user to run a destructive operation again.
 */
@Composable
private fun RestoreOutcome(finish: RestoreFinish) {
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
                        finish.reason?.let { messageText(it) }
                            ?: stringResource(R.string.restore_failed_unknown)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(
                        if (finish.workerRan) {
                            R.string.restore_failed_partial
                        } else {
                            // A passphrase Thor no longer holds, a salt it cannot decode, an enqueue
                            // that threw. All three are decided before any job exists.
                            R.string.restore_failed_not_started
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            is RestoreFinish.Cancelled -> if (finish.workerRan) {
                // Cancelled after the job had been handed to a built worker — reached by the Cancel
                // action on the ongoing notification, which cancels the work rather than dismissing
                // the notification. It is the arm where "nothing was changed" would be false, and the
                // damage is the same damage a failure leaves.
                Text(
                    text = stringResource(R.string.restore_cancelled_after_start),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.restore_failed_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                // The chain case, and the only place "nothing was changed" is earned: WorkManager
                // cancels a dependent without ever calling `doWork`.
                Text(
                    text = stringResource(R.string.restore_cancelled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
