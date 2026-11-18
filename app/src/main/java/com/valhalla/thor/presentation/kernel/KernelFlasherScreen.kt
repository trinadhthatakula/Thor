// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.kernel

import android.text.format.Formatter
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.kernel.KernelFlashController
import com.valhalla.thor.data.kernel.KernelFlashPhase
import com.valhalla.thor.data.kernel.KernelFlashState
import com.valhalla.thor.data.kernel.KernelSlotSelection
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.RootRefreshController
import com.valhalla.thor.presentation.widgets.TermLoggerContent
import com.valhalla.thor.presentation.widgets.TermLoggerStatus
import com.valhalla.thor.util.UiText
import org.koin.compose.koinInject

private data class FlashConfirmation(val hash: String, val slot: KernelSlotSelection, val current: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KernelFlasherScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes titleRes: Int = R.string.kernel_flasher,
) {
    val controller = koinInject<KernelFlashController>()
    val state by controller.state.collectAsStateWithLifecycle()
    val roots = koinInject<RootRefreshController>()
    val root by roots.state.collectAsStateWithLifecycle()
    LaunchedEffect(root.confirmedRevision) {
        if (root.canAdmitRoot) controller.refreshRecovery()
    }
    val activity = LocalActivity.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::select) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(controller::saveLog) }
    var confirmation by remember { mutableStateOf<FlashConfirmation?>(null) }
    var acknowledged by remember(confirmation) { mutableStateOf(false) }
    var bothAcknowledged by remember(confirmation) { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    val writing = state.phase == KernelFlashPhase.STARTING || state.phase == KernelFlashPhase.FLASHING
    val terminal = writing || state.dispatched || (state.phase == KernelFlashPhase.FAILED && state.log.isNotEmpty())
    val chooseFile = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
    BackHandler(enabled = writing) { }
    DisposableEffect(activity, writing) {
        if (writing) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (writing) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    confirmation?.takeIf { state.phase == KernelFlashPhase.READY }?.let { confirmed ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.kernel_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.filename, style = MaterialTheme.typography.titleMedium)
                    Text(slotLabel(confirmed.slot, confirmed.current), color = MaterialTheme.colorScheme.primary)
                    if (confirmed.slot == KernelSlotSelection.INACTIVE) Text(stringResource(R.string.kernel_inactive_warning))
                    if (confirmed.slot == KernelSlotSelection.BOTH) Text(stringResource(R.string.kernel_both_warning), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.kernel_warning))
                    Text(stringResource(R.string.kernel_confirmation))
                    KernelAcknowledgement(
                        checked = acknowledged,
                        label = stringResource(R.string.kernel_acknowledge),
                        onCheckedChange = { acknowledged = it },
                    )
                    if (confirmed.slot == KernelSlotSelection.BOTH) KernelAcknowledgement(
                        checked = bothAcknowledged,
                        label = stringResource(R.string.kernel_both_acknowledge),
                        onCheckedChange = { bothAcknowledged = it },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = acknowledged && (confirmed.slot != KernelSlotSelection.BOTH || bothAcknowledged) && root.canAdmitRoot, onClick = {
                    controller.confirm(confirmed.hash, confirmed.slot, confirmed.current)
                    confirmation = null
                }) { Text(stringResource(R.string.kernel_flash)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text(stringResource(R.string.kernel_reboot)) },
            text = { Text(stringResource(R.string.kernel_reboot_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmReboot = false; controller.reboot() }) { Text(stringResource(R.string.kernel_reboot)) }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !writing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (terminal && state.log.isNotEmpty()) {
                        TextButton(onClick = { saveLog.launch("thor-kernel-flash.log") }, enabled = !writing) {
                            Text(stringResource(R.string.kernel_save_log))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!terminal) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!root.canAdmitRoot) {
                            Text(stringResource(when (root.refreshStatus) {
                                RootRefreshStatus.CHECKING -> R.string.root_refresh_checking
                                RootRefreshStatus.BUSY -> R.string.root_refresh_busy
                                RootRefreshStatus.TIMED_OUT -> R.string.root_refresh_timed_out
                                RootRefreshStatus.FAILED -> R.string.root_refresh_failed
                                RootRefreshStatus.IDLE -> R.string.kernel_root_required
                            }), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = { roots.requestRefresh() },
                                enabled = root.refreshStatus != RootRefreshStatus.CHECKING && !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.kernel_refresh_root)) }
                            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.exit))
                            }
                        } else Button(
                            onClick = {
                                if (state.phase == KernelFlashPhase.READY) {
                                    confirmation = FlashConfirmation(state.sha256, state.slotSelection, state.slotInfo.current)
                                } else chooseFile()
                            },
                            enabled = root.canAdmitRoot && !state.busy && !state.dispatched,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(if (state.phase == KernelFlashPhase.READY) R.string.kernel_flash else R.string.kernel_choose))
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if (terminal) {
            KernelFlashTerminal(state, contentModifier.navigationBarsPadding(), onBack, onChoose = chooseFile, onReboot = { confirmReboot = true }, canReboot = root.canAdmitRoot)
        } else {
            KernelFlashSetup(state, root.canAdmitRoot, contentModifier, chooseFile, controller::selectSlot)
        }
    }
}

@Composable
private fun KernelFlashSetup(
    state: KernelFlashState, hasRoot: Boolean, modifier: Modifier,
    onChoose: () -> Unit, onSlot: (KernelSlotSelection) -> Unit,
) {
    val context = LocalContext.current
    var showDetails by remember(state.sha256) { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.shield), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AnyKernel3", style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.kernel_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.kernel_package_heading), style = MaterialTheme.typography.titleMedium)
                    Text(if (state.phase == KernelFlashPhase.READY) state.filename else stringResource(R.string.kernel_choose), style = MaterialTheme.typography.titleLarge)
                    if (state.phase == KernelFlashPhase.PREPARING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.kernel_preparing))
                    } else if (state.phase == KernelFlashPhase.READY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.check_circle), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(Formatter.formatFileSize(context, state.bytes), color = MaterialTheme.colorScheme.primary)
                        }
                        Row {
                            TextButton(onClick = onChoose, enabled = hasRoot) { Text(stringResource(R.string.kernel_change_package)) }
                            TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(R.string.kernel_package_details)) }
                        }
                        if (showDetails) SelectionContainer {
                            Text(stringResource(R.string.kernel_hash, state.sha256), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(stringResource(R.string.kernel_requirements), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (state.phase == KernelFlashPhase.READY) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 12.dp).selectableGroup()) {
                    Text(stringResource(R.string.kernel_slot_heading), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    val explicitSlots = state.supportsSlotSelection && state.slotInfo.current != null && state.slotInfo.isAbDevice == true
                    KernelSlotSelection.entries.forEach { slot ->
                        val enabled = slot == KernelSlotSelection.PACKAGE_DEFAULT || explicitSlots
                        Row(
                            Modifier.fillMaxWidth().selectable(state.slotSelection == slot, enabled = enabled, role = Role.RadioButton, onClick = { onSlot(slot) })
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.slotSelection == slot, onClick = null, enabled = enabled)
                            Text(slotLabel(slot, state.slotInfo.current), Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    Text(
                        stringResource(when {
                            state.slotInfo.isAbDevice == false -> R.string.kernel_single_slot
                            state.slotInfo.current == null -> R.string.kernel_slot_unknown
                            !state.supportsSlotSelection -> R.string.kernel_slot_unsupported
                            state.slotSelection == KernelSlotSelection.BOTH -> R.string.kernel_both_warning
                            state.slotSelection == KernelSlotSelection.INACTIVE -> R.string.kernel_inactive_warning
                            else -> R.string.kernel_slot_description
                        }),
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.kernel_before_flash), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.kernel_safety_summary), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun KernelFlashTerminal(
    state: KernelFlashState, modifier: Modifier, onBack: () -> Unit, onChoose: () -> Unit,
    onReboot: () -> Unit, canReboot: Boolean,
) {
    val statusText = when (state.phase) {
        KernelFlashPhase.STARTING -> R.string.kernel_starting
        KernelFlashPhase.FLASHING -> R.string.kernel_running
        KernelFlashPhase.SUCCESS -> R.string.kernel_success
        KernelFlashPhase.UNKNOWN -> R.string.kernel_unknown
        else -> R.string.kernel_failed
    }
    val logLines = remember(state.log, state.phase, state.error) {
        state.log.flatMap { it.lines() }
            .map { it.removePrefix("ui_print ").trimEnd() }
            .filter { it.isNotBlank() && it != "ui_print" }
            .map { UiText.DynamicString(it) } +
            listOf(UiText.StringResource(statusText)) + listOfNotNull(state.error?.let { UiText.DynamicString(it) })
    }
    TermLoggerContent(
        title = UiText.StringResource(when (state.phase) {
            KernelFlashPhase.SUCCESS -> R.string.done
            else -> R.string.kernel_flasher
        }),
        logs = logLines,
        status = when {
            state.busy -> TermLoggerStatus.ACTIVE
            state.phase == KernelFlashPhase.SUCCESS -> TermLoggerStatus.SUCCESS
            else -> TermLoggerStatus.NEUTRAL
        },
        modifier = modifier,
        wrapLines = true,
    ) {
        if (!state.busy) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.slotSelection == KernelSlotSelection.INACTIVE) Text(stringResource(R.string.kernel_inactive_warning), style = MaterialTheme.typography.bodySmall)
                if (state.slotSelection == KernelSlotSelection.BOTH && state.phase != KernelFlashPhase.SUCCESS && state.passes.any { it.phase == KernelFlashPhase.SUCCESS }) {
                    Text(stringResource(R.string.kernel_both_partial), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.dispatched) Text(stringResource(R.string.kernel_boot_lock), style = MaterialTheme.typography.bodySmall)
                if (state.phase == KernelFlashPhase.SUCCESS) {
                    Button(onClick = onReboot, enabled = canReboot, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.kernel_reboot)) }
                } else if (!state.dispatched) {
                    Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.kernel_choose)) }
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun slotLabel(slot: KernelSlotSelection, current: String?): String = when (slot) {
    KernelSlotSelection.PACKAGE_DEFAULT -> stringResource(R.string.kernel_slot_default)
    KernelSlotSelection.CURRENT -> stringResource(R.string.kernel_slot_current, current?.removePrefix("_")?.uppercase() ?: "—")
    KernelSlotSelection.INACTIVE -> stringResource(R.string.kernel_slot_inactive, when (current) { "_a" -> "B"; "_b" -> "A"; else -> "—" })
    KernelSlotSelection.BOTH -> stringResource(R.string.kernel_slot_both)
}

@Composable
private fun KernelAcknowledgement(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
}
