// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.presentation.common.JobFinish
import com.valhalla.thor.presentation.common.JobRunningFrame
import com.valhalla.thor.presentation.common.RequestNotificationsWhenJobStarts
import com.valhalla.thor.presentation.utils.AppIconModel
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** How long a finished export stays on screen before the sheet closes itself. */
private const val SUCCESS_LINGER_MS = 3_000L

/**
 * Destination picker + explainer for exporting an installed app's bundle. Self-contained
 * (hosts its own SAF picker and Koin dependencies); shown from the App Info surfaces.
 *
 * The Export button hands the work to `AppExportWorker` and this sheet becomes a watcher. That is the
 * whole of the change from the version that called `ExportAppUseCase` inline and toasted the result:
 * a toast needs the process to still be in the foreground when the export finishes, which for a 4 GB
 * game it very often is not. The job's notification is now the report, and it arrives whether or not
 * anyone is looking at this sheet — which is also why the running frame's Background button is an
 * invitation rather than an apology.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(appInfo: AppInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exportUseCase = koinInject<ExportAppUseCase>()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val systemRepository = koinInject<SystemRepository>()
    val scope = rememberCoroutineScope()

    // Scoped to this composition, as `AppBackupSheet` scopes its own: an export sheet opened for a
    // second app must not inherit the first one's phase. Dismissing the sheet clears the view model
    // with the composition, so reopening asks WorkManager again from scratch — which is exactly what
    // `attach` below is for.
    val viewModel = koinViewModel<ExportViewModel>(
        viewModelStoreOwner = rememberViewModelStoreOwner()
    )
    val phase by viewModel.phase.collectAsStateWithLifecycle()

    // Two options, never three. The native container for this app — .apk for a monolithic app,
    // .apks for a split one — plus .xapk, which is meaningful either way because it is the format
    // other installers (SAI, APKPure) read. The third is always the wrong offer: .apks around a
    // single base apk is a zip that buys nothing, and a monolithic .apk of a split app silently
    // drops the config splits and produces an install that will not run. So the row is shown for
    // every app rather than only for split ones; what changes is which container it opposes .xapk to.
    val formatOptions = remember(appInfo.packageName) {
        listOf(BundleFormat.autoFor(appInfo), BundleFormat.XAPK)
    }

    // Resource values hoisted to composable scope so they can be read inside the
    // non-composable lambdas below (remember/coroutine/onClick) where stringResource
    // cannot be called.
    val defaultDestLabel = stringResource(R.string.export_dest_downloads)

    var targetLabel by remember { mutableStateOf(defaultDestLabel) }
    // Defaults to autoFor(), i.e. the format the builder has always picked on its own, so an
    // export where nobody touches the row is byte-for-byte what shipped before the selector existed.
    var format by remember(appInfo.packageName) { mutableStateOf(formatOptions.first()) }
    // null while the probe is in flight — distinct from ObbProbe.None, which is an answer.
    var obbProbe by remember(appInfo.packageName) { mutableStateOf<ObbProbe?>(null) }

    LaunchedEffect(Unit) { targetLabel = exportUseCase.currentTargetLabel() }

    LaunchedEffect(appInfo.packageName) {
        obbProbe = systemRepository.probeObb(appInfo.packageName)
    }

    // Pick up an export of this app that is already running — the user backgrounded the sheet and came
    // back. Without it the form reappears over a live job and a second tap appends a duplicate to the
    // chain, both writing into the same staging directory.
    LaunchedEffect(appInfo.packageName) { viewModel.attach(appInfo.packageName) }

    // The job's only surface once this sheet is gone is a notification, and `ThorJobNotifications`
    // returns early when notifications are off — so an export started in that state runs invisibly and
    // finishes invisibly. Asked at the moment the job is accepted, not when the button is pressed.
    RequestNotificationsWhenJobStarts(jobActive = phase.running)

    // There used to be a LaunchedEffect here that forced the selection off XAPK whenever the probe
    // came back Undetermined. It went with the chip's `enabled` gate below and with the matching
    // throw in AppBundleBuilderImpl: together they made "Thor could not read Android/obb" refuse the
    // format outright. Most apps have no expansion files, so the verdict we could not read was
    // usually "there is nothing to read", and the refusal fired on the ordinary case. Per the owner
    // the export proceeds and says what it did; see `shouldWarnUnreadableObb`.

    val runExport = {
        viewModel.start(
            packageName = appInfo.packageName,
            label = appInfo.appName ?: appInfo.packageName,
            format = format,
        )
    }

    // On API <= 28, writing to the public Downloads directory needs WRITE_EXTERNAL_STORAGE
    // granted at runtime. Run the export regardless of the grant result: SAF export still
    // works when denied, and the job itself surfaces success/failure.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { runExport() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // runCatching because the persist can be refused: the grant table is capped (128 entries
            // per app on most builds) and some providers hand back a tree they will not persist at
            // all. Unguarded, that SecurityException propagates out of the picker callback and takes
            // the Activity down at the moment the user picked a folder. Saving the URI anyway is
            // deliberate — the grant is live for this process either way, so the export the user is
            // about to run still works; what is lost is remembering the folder next launch, and
            // `openSession` already clears a saved tree it cannot write to.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { Logger.w("Export", "could not persist $uri: $it") }
            scope.launch {
                preferenceRepository.setExportDirUri(uri.toString())
                targetLabel = exportUseCase.currentTargetLabel()
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
            // Section header
            Text(
                text = stringResource(R.string.action_export).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // App identity card + format badge. Outside the `when` below, so the running and outcome
            // frames still say which app they are about — a notification can arrive over any screen
            // and the user coming back to this sheet may have started more than one thing.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = AppIconModel(appInfo.packageName),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName ?: appInfo.packageName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = appInfo.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = ".${format.extension}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Three frames, and the order settles the one pair that can overlap: `running` outranks a
            // `finished` left over from a previous export, which the reattach collector can pick up
            // before `watch` clears the banner.
            val finished = phase.finished
            when {
                // The form is *gone* while the job runs, not disabled. Two chips, a destination row
                // and two buttons used to sit here with `enabled = !exporting` on each — a form the
                // user can read, cannot use, and has to scroll past to reach the one thing moving.
                //
                // `onBackground` is `onDismiss` and nothing else: dismissing drops this watcher, the
                // worker carries on, and its notification takes over the reporting.
                phase.running -> JobRunningFrame(
                    phase = phase,
                    queuedLabel = stringResource(R.string.export_job_queued),
                    backgroundLabel = stringResource(R.string.export_job_background),
                    backgroundDescription = stringResource(R.string.export_job_background_desc),
                    onBackground = onDismiss,
                )

                finished is JobFinish.Succeeded -> {
                    // Three seconds, then the sheet closes itself: the file is written and the form
                    // underneath has nothing left to offer except writing it again. Keyed on `Unit`,
                    // so the timer belongs to this frame rather than outliving it.
                    LaunchedEffect(Unit) {
                        delay(SUCCESS_LINGER_MS)
                        onDismiss()
                    }
                    ExportOutcome(finish = finished, onDismiss = onDismiss)
                }

                finished != null -> ExportOutcome(
                    finish = finished,
                    // `dismissResult`, not `onDismiss`: a failure puts the user back on a form they
                    // may want to retry from — a different destination, a different format — so
                    // clearing the banner is the right thing rather than closing the sheet.
                    onDismiss = viewModel::dismissResult,
                )

                else -> {
                    // Format
                    Text(
                        text = stringResource(R.string.export_format).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        formatOptions.forEach { option ->
                            FilterChip(
                                selected = option == format,
                                onClick = { format = option },
                                // Nothing disables a chip now. The probe verdict never did — a
                                // disabled .xapk chip was how "we could not read Android/obb" reached
                                // the user, and it read as "this app cannot be exported as .xapk"
                                // when the truth was usually that there was nothing to pack — and the
                                // export in flight no longer needs to, because this whole frame is
                                // replaced while one is running.
                                // A file extension, not copy — the same token in every locale, so it
                                // is built from BundleFormat rather than from a translated string.
                                label = { Text(".${option.extension}") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }

                    // Plain-language explanation of the selected format, plus what the .xapk will
                    // actually carry. This is the only place the user learns whether their game data
                    // is going in, so it has to follow the selection rather than sit above it.
                    Text(
                        text = stringResource(
                            when (format) {
                                BundleFormat.APK -> R.string.export_explain_apk
                                BundleFormat.APKS -> R.string.export_explain_apks
                                BundleFormat.XAPK -> R.string.export_explain_xapk
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // The three OBB notes, in the order the user needs them: how much is going in,
                    // what is being left out, and that we could not tell either way. All three are
                    // notes and none of them blocks the Export button.
                    val obbBytesToShow = obbSizeBytesToShow(format, obbProbe)
                    if (obbBytesToShow > 0) {
                        Text(
                            text = stringResource(
                                R.string.export_obb_included,
                                Formatter.formatShortFileSize(context, obbBytesToShow)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (shouldNotePartialObb(format, obbProbe)) {
                        // Not a refusal. The format cannot carry anything but .obb files, so a bundle
                        // without those extras is complete by the format's own definition — the user
                        // is told, and decides.
                        Text(
                            text = stringResource(R.string.export_obb_partial),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (shouldWarnUnreadableObb(format, obbProbe)) {
                        Text(
                            text = stringResource(R.string.export_xapk_no_game_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Destination
                    Text(
                        text = stringResource(R.string.export_save_to).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                            )
                            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.storage),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = targetLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(onClick = { picker.launch(null) }) {
                            Icon(
                                painter = painterResource(R.drawable.open_in),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.export_change))
                        }
                    }

                    // Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                // A custom SAF folder writes via DocumentFile and needs no
                                // WRITE_EXTERNAL_STORAGE — only the legacy Downloads path (API <= 28)
                                // does.
                                val usingCustomFolder = targetLabel != defaultDestLabel
                                if (!usingCustomFolder &&
                                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    runExport()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            // No spinner and no "Exporting…" label. The button does not stay on
                            // screen long enough to need either — `phase.running` swaps this whole
                            // frame for JobRunningFrame on the same recomposition.
                            Text(stringResource(R.string.action_export))
                        }
                    }
                }
            }
        }
    }
}

/**
 * How a finished export is reported on the sheet.
 *
 * Four sentences for three outcomes, split on [JobFinish.workerRan] — the same shape as
 * `BackupOutcome`, because the same things can happen and the user needs to tell them apart.
 *
 * | Outcome | Copy |
 * |---|---|
 * | `Succeeded` | it was exported |
 * | `Failed(workerRan = true)` | the worker's own sentence, or "unknown error" |
 * | `Failed(workerRan = false)` | it did not start, so nothing was saved |
 * | `Cancelled(workerRan = true)` | it stopped before finishing; nothing was saved |
 * | `Cancelled(workerRan = false)` | the chain cancelled it before it started |
 *
 * There is no half-written-file sentence, and there should not be: an export writes to a staging
 * directory the builder wipes on entry and only publishes at the end, so a stopped export leaves the
 * destination folder exactly as it found it.
 */
@Composable
private fun ExportOutcome(finish: JobFinish, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (finish) {
            is JobFinish.Succeeded -> Text(
                text = stringResource(R.string.export_job_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            is JobFinish.Failed -> Text(
                text = if (finish.workerRan) {
                    stringResource(
                        R.string.export_failed,
                        finish.reason ?: stringResource(R.string.export_failed_unknown)
                    )
                } else {
                    // The enqueue returned no id, or the block around it threw. Both are decided
                    // before a job exists, so there is no worker sentence to show and none is faked.
                    stringResource(R.string.export_failed_not_started)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            is JobFinish.Cancelled -> Text(
                text = stringResource(
                    if (finish.workerRan) {
                        R.string.export_job_stopped
                    } else {
                        // WorkManager cancels the dependents of a prerequisite that fails, and every
                        // Thor job is appended to one chain — so the common cancel is an export that
                        // was queued behind a failing job and never entered `doWork`.
                        R.string.export_failed_not_started
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        // The dismiss the failure frames need: without it a banner sits over the form until the sheet
        // is closed, and the retry the user wants is behind it. Harmless on the success frame, which
        // dismisses itself three seconds later anyway — this is the button that skips the wait.
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.dismiss))
        }
    }
}

/**
 * How many bytes of game data the sheet should tell the user are going in, or 0 for "say nothing".
 *
 * Hoisted out of the composable so the three OBB notes are decidable without a device. Each takes
 * the probe as `ObbProbe?` because **null is a fourth state at this layer and only at this layer**:
 * the probe is in flight. It is not [ObbProbe.Undetermined] — that is an answer — and a sheet that
 * treated it as one would flash a "could not read" note for the length of every probe.
 *
 * [format] is read rather than assumed, because the chips are live: the user can select `.apk`
 * after the probe has already answered `Present`, and a size line still on screen would be
 * describing bytes that container does not carry.
 */
internal fun obbSizeBytesToShow(format: BundleFormat, probe: ObbProbe?): Long {
    if (format != BundleFormat.XAPK) return 0L
    val present = probe as? ObbProbe.Present ?: return 0L
    return present.files.sumOf { it.sizeBytes }
}

/**
 * Whether to note that the app's `Android/obb` holds entries the `.xapk` format cannot carry.
 *
 * Distinct from [obbSizeBytesToShow] being 0: a directory can hold nothing but subdirectories, in
 * which case there are no bytes to announce and there is still something being left behind. That is
 * [ObbProbe.Present] with an empty `files` and a non-zero `otherEntryCount`, and it is the reason
 * these are two functions rather than one.
 */
internal fun shouldNotePartialObb(format: BundleFormat, probe: ObbProbe?): Boolean =
    format == BundleFormat.XAPK && (probe as? ObbProbe.Present)?.otherEntryCount?.let { it > 0 } == true

/**
 * Whether to note that Thor could not read the app's game data at all.
 *
 * This is what is left of the old refusal. `Undetermined` used to disable the `.xapk` chip, force
 * the selection away from it, and throw in the builder; now it prints one line and the user decides.
 * The verdict is still not [ObbProbe.None] — that prints nothing, because nothing is what there is
 * to say — which is the distinction `ObbProbe` exists to keep.
 *
 * Gated on [format] because the note is about what a `.xapk` will contain. An `.apk`/`.apks` export
 * never carries expansions, so telling the user we could not read them would be answering a question
 * they did not ask.
 */
internal fun shouldWarnUnreadableObb(format: BundleFormat, probe: ObbProbe?): Boolean =
    format == BundleFormat.XAPK && probe is ObbProbe.Undetermined
