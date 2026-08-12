// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.presentation.utils.AppIconModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Destination picker + explainer for exporting an installed app's bundle. Self-contained
 * (hosts its own SAF picker and Koin dependencies); shown from the App Info surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(appInfo: AppInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exportUseCase = koinInject<ExportAppUseCase>()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val systemRepository = koinInject<SystemRepository>()
    val scope = rememberCoroutineScope()

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
    val exportSavedFormat = stringResource(R.string.export_saved)
    val exportFailedFormat = stringResource(R.string.export_failed)
    val exportFailedUnknown = stringResource(R.string.export_failed_unknown)

    var targetLabel by remember { mutableStateOf(defaultDestLabel) }
    var exporting by remember { mutableStateOf(false) }
    // Defaults to autoFor(), i.e. the format the builder has always picked on its own, so an
    // export where nobody touches the row is byte-for-byte what shipped before the selector existed.
    var format by remember(appInfo.packageName) { mutableStateOf(formatOptions.first()) }
    // null while the probe is in flight — distinct from ObbProbe.None, which is an answer.
    var obbProbe by remember(appInfo.packageName) { mutableStateOf<ObbProbe?>(null) }

    LaunchedEffect(Unit) { targetLabel = exportUseCase.currentTargetLabel() }

    LaunchedEffect(appInfo.packageName) {
        obbProbe = systemRepository.probeObb(appInfo.packageName)
    }

    // There used to be a LaunchedEffect here that forced the selection off XAPK whenever the probe
    // came back Undetermined. It went with the chip's `enabled` gate below and with the matching
    // throw in AppBundleBuilderImpl: together they made "Thor could not read Android/obb" refuse the
    // format outright. Most apps have no expansion files, so the verdict we could not read was
    // usually "there is nothing to read", and the refusal fired on the ordinary case. Per the owner
    // the export proceeds and says what it did; see `shouldWarnUnreadableObb`.

    val runExport = {
        exporting = true
        scope.launch {
            val result = exportUseCase(appInfo, format)
            exporting = false
            result
                .onSuccess {
                    Toast.makeText(
                        context,
                        exportSavedFormat.format(it),
                        Toast.LENGTH_LONG
                    ).show()
                    onDismiss()
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        exportFailedFormat.format(it.message ?: exportFailedUnknown),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    // On API <= 28, writing to the public Downloads directory needs WRITE_EXTERNAL_STORAGE
    // granted at runtime. Run the export regardless of the grant result: SAF export still
    // works when denied, and the export itself surfaces success/failure.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { runExport() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
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

            // App identity card + format badge
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
                        // Only the export in flight disables a chip now. The probe verdict no longer
                        // does: a disabled .xapk chip was how "we could not read Android/obb" reached
                        // the user, and it read as "this app cannot be exported as .xapk" when the
                        // truth was usually that there was nothing to pack. The verdict is still
                        // shown — as a note under the row, which the user can act on — rather than
                        // enforced as a refusal.
                        enabled = !exporting,
                        // A file extension, not copy — the same token in every locale, so it is
                        // built from BundleFormat rather than from a translated string.
                        label = { Text(".${option.extension}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // Plain-language explanation of the selected format, plus what the .xapk will actually
            // carry. This is the only place the user learns whether their game data is going in,
            // so it has to follow the selection rather than sit above it.
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

            // The three OBB notes, in the order the user needs them: how much is going in, what is
            // being left out, and — the case this fix is about — that we could not tell either way.
            // All three are notes and none of them blocks the Export button.
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
                // without those extras is complete by the format's own definition — the user is
                // told, and decides.
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
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
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
                FilledTonalButton(
                    onClick = { picker.launch(null) },
                    enabled = !exporting
                ) {
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
                    enabled = !exporting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    enabled = !exporting,
                    onClick = {
                        // A custom SAF folder writes via DocumentFile and needs no
                        // WRITE_EXTERNAL_STORAGE — only the legacy Downloads path (API <= 28) does.
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
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_in_progress))
                    } else {
                        Text(stringResource(R.string.action_export))
                    }
                }
            }
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
