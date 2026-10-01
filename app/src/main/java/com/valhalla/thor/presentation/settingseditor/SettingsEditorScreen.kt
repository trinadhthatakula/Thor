// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.settingseditor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.content.ClipData
import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.*
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random

@Composable
fun SettingsEditorScreen(onBack: () -> Unit, viewModel: SettingsEditorViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SettingEntry?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var undo by remember { mutableStateOf<SettingsEditRecord?>(null) }
    val unlocked = state.mode != null && state.consent == true && !state.busy
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text(stringResource(R.string.sett_edit)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.sett_back)) }
        }, actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.busy && state.mode != null) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (state.mode == null) {
                Text(stringResource(R.string.sett_unavailable), Modifier.padding(24.dp))
            } else if (state.consent == null) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showHistory, onClick = { showHistory = false }, label = { Text(stringResource(R.string.sett_values)) })
                    FilterChip(selected = showHistory, onClick = { showHistory = true }, label = { Text(stringResource(R.string.sett_history)) })
                }
                if (!showHistory) {
                    var expanded by remember { mutableStateOf(false) }
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(onClick = { expanded = true }, enabled = !state.busy) { Text(stringResource(state.view.titleRes())) }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            SettingsEditorView.entries.forEach { view ->
                                DropdownMenuItem(text = { Text(stringResource(view.titleRes())) }, onClick = { expanded = false; search = ""; viewModel.select(view) })
                            }
                        }
                    }
                    Text(stringResource(when (state.view) {
                        SettingsEditorView.GLOBAL -> R.string.sett_shared_scope
                        SettingsEditorView.JAVA_PROPERTIES -> R.string.sett_runtime_scope
                        SettingsEditorView.ENVIRONMENT -> R.string.sett_environment_scope
                        SettingsEditorView.ANDROID_PROPERTIES -> R.string.sett_properties_scope
                        else -> R.string.sett_user_scope
                    }, thorUserId), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.sett_search)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    if (state.view.writable) TextButton(onClick = { adding = true }, enabled = unlocked, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.Rounded.Add, null); Text(stringResource(R.string.sett_add))
                    }
                } else Text(stringResource(R.string.sett_history_note), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error || state.conflict || state.executionUnconfirmed || state.outcome != null) {
                    Text(stringResource(when {
                        state.executionUnconfirmed || state.outcome == SettingsEditOutcome.UNCONFIRMED -> R.string.sett_execution_unconfirmed
                        state.conflict || state.outcome == SettingsEditOutcome.CONFLICT -> R.string.sett_conflict
                        state.outcome == SettingsEditOutcome.VERIFIED -> R.string.sett_verified
                        state.outcome == SettingsEditOutcome.REJECTED -> R.string.sett_rejected
                        state.outcome == SettingsEditOutcome.UNKNOWN -> R.string.sett_unknown
                        else -> R.string.sett_error
                    }), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showHistory) {
                        items(state.history, key = { it.id }) { record ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(record.key, style = MaterialTheme.typography.titleMedium)
                                    Text("${stringResource(record.view.titleRes())} · ${record.provider.name} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.timestamp))}", style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(record.outcome.titleRes()))
                                    Text(stringResource(R.string.sett_before) + "\n" + displayValue(record.before))
                                    Text(stringResource(R.string.sett_after) + "\n" + displayValue(record.desired))
                                    TextButton(onClick = { undo = record }, enabled = unlocked && record.outcome == SettingsEditOutcome.VERIFIED && state.history.none { it.undoOf == record.id && it.outcome == SettingsEditOutcome.VERIFIED }) {
                                        Text(stringResource(R.string.sett_undo))
                                    }
                                }
                            }
                        }
                    } else {
                        val visible = state.entries.filter { search.isEmpty() || it.key.contains(search, true) || it.value.orEmpty().contains(search, true) }
                        if (visible.isEmpty() && !state.busy) item { Text(stringResource(R.string.sett_empty)) }
                        items(visible, key = { it.key }) { entry ->
                            Card(onClick = { if (unlocked && state.view.writable) selected = entry }, Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(entry.key, style = MaterialTheme.typography.titleMedium)
                                    Text(displayValue(SettingValue(true, entry.value)), style = MaterialTheme.typography.bodyMedium)
                                    Row {
                                        TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(entry.key, entry.value.orEmpty()))) } }) {
                                            Text(stringResource(R.string.sett_copy))
                                        }
                                        if (state.view.writable) TextButton(onClick = { selected = entry }, enabled = unlocked) { Text(stringResource(R.string.action_edit)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.mode != null && state.consent == false) SettingsEditorConsentSheet(
        busy = state.busy, failed = state.consentWriteFailed, onAccept = viewModel::acceptConsent, onDismiss = onBack)
    if (adding || selected != null) SettingsValueDialog(
        view = state.view, entry = selected, enabled = unlocked,
        onDismiss = { adding = false; selected = null },
        onChange = { key, expected, desired -> viewModel.change(key, expected, desired); adding = false; selected = null })
    undo?.let { record ->
        AlertDialog(onDismissRequest = { undo = null }, title = { Text(stringResource(R.string.sett_undo)) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sett_undo_note)); Text(record.key)
                Text(stringResource(R.string.sett_before) + "\n" + displayValue(record.desired))
                Text(stringResource(R.string.sett_after) + "\n" + displayValue(record.before))
            } }, confirmButton = { TextButton(onClick = { viewModel.undo(record.id); undo = null }, enabled = unlocked) { Text(stringResource(R.string.sett_undo)) } },
            dismissButton = { TextButton(onClick = { undo = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun SettingsEditorConsentSheet(busy: Boolean, failed: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val a = rememberSaveable { Random.nextInt(3, 10) }
    val b = rememberSaveable { Random.nextInt(2, 9) }
    var answer by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sett_warning_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.sett_warning))
            Text(stringResource(R.string.extension_consent_math_prompt, a, b))
            OutlinedTextField(answer, { answer = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.sett_answer)) })
            if (failed) Text(stringResource(R.string.sett_consent_failed), color = MaterialTheme.colorScheme.error)
            Button(onClick = onAccept, enabled = !busy && answer.trim().toIntOrNull() == a + b) { Text(stringResource(R.string.extension_consent_accept)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun SettingsValueDialog(view: SettingsEditorView, entry: SettingEntry?, enabled: Boolean, onDismiss: () -> Unit, onChange: (String, SettingValue, SettingValue) -> Unit) {
    var key by rememberSaveable(entry?.key) { mutableStateOf(entry?.key.orEmpty()) }
    var value by rememberSaveable(entry?.key) { mutableStateOf(entry?.value.orEmpty()) }
    var sqlNull by rememberSaveable(entry?.key) { mutableStateOf(entry != null && entry.value == null) }
    var deleting by rememberSaveable(entry?.key) { mutableStateOf(false) }
    var preview by rememberSaveable(entry?.key) { mutableStateOf(false) }
    var riskAccepted by rememberSaveable(entry?.key) { mutableStateOf(false) }
    val expected = if (entry == null) SettingValue.ABSENT else SettingValue(true, entry.value)
    val desired = if (deleting) SettingValue.ABSENT else SettingValue(true, if (sqlNull) null else value)
    val highRisk = key.contains("debug", true) || key.contains("adb", true) || key.contains("accessibility", true) || key.contains("input", true) || key.contains("enabled", true) || key.contains("display", true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (preview) R.string.sett_preview else R.string.sett_edit_value)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(view.titleRes()))
                Text(stringResource(if (view == SettingsEditorView.GLOBAL) R.string.sett_shared_scope else R.string.sett_user_scope, thorUserId))
                if (!preview) {
                    OutlinedTextField(key, { key = it }, enabled = entry == null && enabled, singleLine = true, label = { Text(stringResource(R.string.sett_key)) })
                    OutlinedTextField(value, { value = it }, enabled = !deleting && !sqlNull && enabled, label = { Text(stringResource(R.string.sett_value)) }, modifier = Modifier.fillMaxWidth())
                    Row { Checkbox(sqlNull, { sqlNull = it }, enabled = !deleting && enabled); Text(stringResource(R.string.sett_null)) }
                    if (entry != null) Row { Checkbox(deleting, { deleting = it }, enabled = enabled); Text(stringResource(R.string.sett_delete)) }
                    Text(stringResource(R.string.sett_key_rules), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(key)
                    Text(stringResource(R.string.sett_before) + "\n" + displayValue(expected))
                    Text(stringResource(R.string.sett_after) + "\n" + displayValue(desired))
                    if (deleting) Text(stringResource(R.string.sett_delete_note))
                    if (highRisk) {
                        Text(stringResource(R.string.sett_high_risk), color = MaterialTheme.colorScheme.error)
                        Row { Checkbox(riskAccepted, { riskAccepted = it }, enabled = enabled); Text(stringResource(R.string.sett_risk_accept)) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { if (preview) onChange(key, expected, desired) else preview = true },
            enabled = enabled && editableSettingKey(key) && value.length <= 65536 && (!preview || !highRisk || riskAccepted)) {
            Text(stringResource(if (preview) R.string.sett_apply else R.string.sett_preview))
        } }, dismissButton = { TextButton(onClick = { if (preview) { preview = false; riskAccepted = false } else onDismiss() }) { Text(stringResource(if (preview) R.string.sett_back else R.string.cancel)) } })
}

@Composable
private fun displayValue(state: SettingValue): String = when {
    !state.present -> stringResource(R.string.sett_absent)
    state.value == null -> stringResource(R.string.sett_null)
    state.value.isEmpty() -> stringResource(R.string.sett_empty_value)
    else -> state.value
}
internal fun SettingsEditorView.titleRes(): Int = when (this) {
    SettingsEditorView.SYSTEM -> R.string.sett_system
    SettingsEditorView.SECURE -> R.string.sett_secure
    SettingsEditorView.GLOBAL -> R.string.sett_global
    SettingsEditorView.ANDROID_PROPERTIES -> R.string.sett_android_properties
    SettingsEditorView.JAVA_PROPERTIES -> R.string.sett_java_properties
    SettingsEditorView.ENVIRONMENT -> R.string.sett_environment
}
private fun SettingsEditOutcome.titleRes(): Int = when (this) {
    SettingsEditOutcome.UNCONFIRMED -> R.string.sett_execution_unconfirmed
    SettingsEditOutcome.PENDING -> R.string.sett_unknown
    SettingsEditOutcome.VERIFIED -> R.string.sett_verified
    SettingsEditOutcome.REJECTED -> R.string.sett_rejected
    SettingsEditOutcome.CONFLICT -> R.string.sett_conflict
    SettingsEditOutcome.UNKNOWN -> R.string.sett_unknown
}
