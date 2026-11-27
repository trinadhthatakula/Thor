// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.FreezerUser
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.ScopedFreezerApp
import com.valhalla.thor.presentation.navigation.LocalFloatingNavigationClearance
import com.valhalla.thor.presentation.queue.QueueNavigationButton
import com.valhalla.thor.presentation.widgets.AppTypeFilter

@Composable
internal fun FreezerUserPicker(
    users: List<FreezerUser>,
    selected: FreezerUser?,
    onSelected: (FreezerUser?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (users.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.let { freezerUserName(it) } ?: stringResource(R.string.freezer_current_user))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.freezer_current_user)) },
                onClick = { expanded = false; onSelected(null) },
                enabled = selected != null,
            )
            users.forEach { user ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(freezerUserName(user))
                            Text(
                                stringResource(if (user.workProfile) R.string.freezer_work_profile else R.string.freezer_secondary_user),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = { expanded = false; onSelected(user) },
                    enabled = user.serial != selected?.serial,
                )
            }
        }
    }
}

@Composable
internal fun freezerUserName(user: FreezerUser): String =
    user.name.ifBlank { stringResource(R.string.freezer_user_number, user.id) }

private data class ScopedFreezerAction(
    val operation: PrivilegeSweepOperation,
    val names: List<String>,
    val add: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrossUserFreezerContent(
    user: FreezerUser,
    state: CrossUserFreezerState,
    viewModel: CrossUserFreezerViewModel,
    onNavigateToQueue: () -> Unit,
    onScopeSelected: (FreezerUser?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppListType.USER) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var showAdd by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<FreezeProfile?>(null) }
    var editorSearch by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<ScopedFreezerAction?>(null) }
    var menuApps by remember { mutableStateOf(emptyList<ScopedFreezerApp>()) }
    val requesting = state.requests.any { it.targetUserSerial == user.serial &&
        (it.phase == PrivilegeSweepPhase.QUEUED || it.phase == PrivilegeSweepPhase.RUNNING) }
    val busy = state.loading || state.saving || requesting
    val name = freezerUserName(user)
    val listed = state.apps.filter { app ->
        (app.packageName in state.watchlist || app.isSuspended || (app.isDisabled && app.canRestore)) &&
            app.isSystem == (filter == AppListType.SYSTEM) &&
            (app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
    }
    val editorApps = remember(state.apps) {
        state.apps.map {
            AppInfo(appName = it.label, packageName = it.packageName, enabled = !it.isDisabled,
                isSystem = it.isSystem, isSuspended = it.isSuspended)
        }
    }
    val navigationBottom = LocalFloatingNavigationClearance.current
    Column(
        modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = navigationBottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.freezer), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            QueueNavigationButton(onClick = onNavigateToQueue)
            AppTypeFilter(selection = filter, onSelectionChanged = { filter = it; selection = emptySet() }, multiSelectEnabled = false)
        }
        FreezerUserPicker(state.users, user, onScopeSelected)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; selection = emptySet() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.search_apps)) },
            singleLine = true,
        )
        if (state.loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        if (listed.isEmpty() && !state.loading) {
            Text(stringResource(if (state.watchlist.isEmpty()) R.string.no_apps_in_freezer else R.string.no_matching_apps_freezer))
        }
        if (listed.isNotEmpty()) TextButton(onClick = {
            selection = if (listed.all { it.packageName in selection }) emptySet()
                else listed.mapTo(mutableSetOf()) { it.packageName }
        }) { Text(stringResource(R.string.select_all)) }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(listed, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = {
                        Text(stringResource(when {
                            app.isSuspended -> R.string.status_suspended
                            app.isDisabled -> R.string.frozen
                            else -> R.string.active
                        }))
                    },
                    leadingContent = {
                        Checkbox(app.packageName in selection, { checked ->
                            selection = if (checked) selection + app.packageName else selection - app.packageName
                        })
                    },
                    trailingContent = {
                        TextButton(enabled = !busy, onClick = { menuApps = listOf(app) }) {
                            Text(stringResource(R.string.more_options))
                        }
                    },
                )
                HorizontalDivider()
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !busy && selection.isNotEmpty(), onClick = {
                menuApps = listed.filter { it.packageName in selection }
            }) { Text(stringResource(R.string.more_options)) }
            OutlinedButton(enabled = !busy, onClick = { showProfiles = true }) {
                Text(stringResource(R.string.freeze_profiles))
            }
            OutlinedButton(enabled = !busy, onClick = { showAdd = true }) {
                Text(stringResource(R.string.add_to_freezer))
            }
        }
    }
    if (showAdd) ScopedFreezerAddSheet(name, state, onDismiss = { showAdd = false }, onApp = { app ->
        showAdd = false
        action = ScopedFreezerAction(PrivilegeSweepOperation.FREEZE, listOf(app.packageName), add = true)
    })
    if (menuApps.isNotEmpty()) {
        val apps = menuApps
        ModalBottomSheet(
            onDismissRequest = { menuApps = emptyList() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                val options = buildList {
                    if (apps.any { !it.isDisabled && it.canFreeze }) add(PrivilegeSweepOperation.FREEZE)
                    if (apps.any { it.isDisabled && it.canRestore }) add(PrivilegeSweepOperation.UNFREEZE)
                    if (apps.any { !it.isSuspended && it.canFreeze }) add(PrivilegeSweepOperation.SUSPEND)
                    if (apps.any { it.isSuspended }) add(PrivilegeSweepOperation.UNSUSPEND)
                }
                options.forEach { op ->
                    TextButton(onClick = {
                        val targets = apps.filter { app ->
                            when (op) {
                                PrivilegeSweepOperation.FREEZE -> !app.isDisabled && app.canFreeze
                                PrivilegeSweepOperation.UNFREEZE -> app.isDisabled && app.canRestore
                                PrivilegeSweepOperation.SUSPEND -> !app.isSuspended && app.canFreeze
                                PrivilegeSweepOperation.UNSUSPEND -> app.isSuspended
                                else -> false
                            }
                        }
                        action = ScopedFreezerAction(op, targets.map { it.packageName })
                        menuApps = emptyList()
                    }) { Text(stringResource(op.labelRes())) }
                }
                apps.singleOrNull()?.takeUnless { it.isDisabled || it.isSuspended }?.let { app ->
                    TextButton(onClick = { menuApps = emptyList(); viewModel.remove(user, app) }) {
                        Text(stringResource(R.string.action_remove))
                    }
                }
            }
        }
    }
    if (showProfiles) FreezeProfilesSheet(
        profiles = state.profiles,
        allApps = editorApps,
        runningRequests = emptyList(),
        hasPrivilege = !busy,
        onRun = { id, op, mode ->
            val profile = state.profiles.firstOrNull { it.id == id }
            if (profile != null) {
                val selectedOperation = when {
                    op == BulkOp.UNFREEZE -> PrivilegeSweepOperation.UNFREEZE
                    mode == FreezerMode.SUSPEND -> PrivilegeSweepOperation.SUSPEND
                    else -> PrivilegeSweepOperation.FREEZE
                }
                showProfiles = false
                action = ScopedFreezerAction(selectedOperation, profile.packageNames)
            }
        },
        onKill = {},
        onPin = null,
        isProfilePinned = { false },
        onCreate = { showProfiles = false; editorSearch = ""; editor = FreezeProfile(0, "", emptyList()) },
        onEdit = { showProfiles = false; editorSearch = ""; editor = it },
        onDelete = { viewModel.delete(user, it) },
        onDismiss = { showProfiles = false },
        canForceStop = false,
        isWorking = state.saving,
        scopeLabel = name,
    )
    editor?.let { profile ->
        FreezeProfileEditorSheet(
            profile = profile.takeIf { it.id != 0L },
            initialSelection = emptySet(),
            existingNames = state.profiles.filterNot { it.id == profile.id }.map { it.name },
            allApps = editorApps,
            searchQuery = editorSearch,
            onSearchChange = { editorSearch = it },
            isSaving = state.saving,
            onSave = { title, members, icon ->
                viewModel.save(user, FreezeProfile(profile.id, title, members, icon)) {
                    editor = null
                    showProfiles = true
                }
            },
            onDismiss = { editor = null; showProfiles = true },
            scopeLabel = name,
        )
    }
    action?.let { request ->
        val actionName = stringResource(if (request.add) R.string.add_to_freezer else request.operation.labelRes())
        val appLabel = request.names.singleOrNull()?.let { pkg -> state.apps.find { it.packageName == pkg }?.label ?: pkg }
            ?: stringResource(R.string.selected_count, request.names.size)
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(actionName) },
            text = { Text(stringResource(R.string.freezer_user_confirmation, appLabel, name)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    action = null
                    selection = emptySet()
                    viewModel.run(user, request.names, request.operation, request.add)
                }) { Text(actionName) }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopedFreezerAddSheet(
    userName: String,
    state: CrossUserFreezerState,
    onDismiss: () -> Unit,
    onApp: (ScopedFreezerApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.add_to_freezer), style = MaterialTheme.typography.titleLarge)
            Text(userName, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_apps)) }, singleLine = true)
            val candidates = state.apps.filter {
                it.packageName !in state.watchlist && it.label.contains(query, ignoreCase = true) &&
                    !it.isDisabled && it.canFreeze
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(candidates, key = { it.packageName }) { app ->
                    TextButton(onClick = { onApp(app) }, modifier = Modifier.fillMaxWidth()) { Text(app.label) }
                }
            }
        }
    }
}

private fun PrivilegeSweepOperation.labelRes(): Int = when (this) {
    PrivilegeSweepOperation.FREEZE -> R.string.action_freeze
    PrivilegeSweepOperation.UNFREEZE -> R.string.action_unfreeze
    PrivilegeSweepOperation.SUSPEND -> R.string.action_suspend
    PrivilegeSweepOperation.UNSUSPEND -> R.string.action_unsuspend
    else -> error("Unsupported cross-user Freezer action")
}
