// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpEntry
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.AppOpsSnapshot
import java.util.Locale
import androidx.compose.ui.res.stringResource

private val editableModes = listOf(
    AppOpMode.ALLOW,
    AppOpMode.IGNORE,
    AppOpMode.DENY,
    AppOpMode.DEFAULT,
    AppOpMode.FOREGROUND,
)

/** Search technical names and permission aliases, then keep relevant entries first in All. */
internal fun visibleAppOps(
    entries: List<AppOpEntry>,
    query: String,
    filter: AppOpsFilter,
): List<AppOpEntry> {
    val needle = query.trim()
    return entries.asSequence()
        .filter { entry ->
            when (filter) {
                AppOpsFilter.RELEVANT -> entry.isRelevant
                AppOpsFilter.CHANGED -> entry.isChanged
                AppOpsFilter.ALL -> true
            }
        }
        .filter { entry ->
            needle.isEmpty() || buildList {
                add(appOpDisplayName(entry.definition))
                add(entry.definition.debugName)
                entry.definition.publicName?.let { add(it) }
                addAll(entry.definition.aliases)
                addAll(entry.definition.relatedPermissions)
            }.any { it.contains(needle, ignoreCase = true) }
        }
        .sortedWith(
            compareByDescending<AppOpEntry> { it.isRelevant }
                .thenByDescending { it.isChanged }
                .thenByDescending { it.permissionRequested }
                .thenByDescending { it.observed }
                .thenBy { appOpDisplayName(it.definition) },
        )
        .toList()
}

/** Device op names are identifiers; this only makes their presentation easier to scan. */
internal fun appOpDisplayName(definition: AppOpDefinition): String =
    definition.debugName.removePrefix("OP_").substringAfterLast(':')
        .lowercase(Locale.ROOT).replace('_', ' ').split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

/** Catalog aliases alternate debug identifiers and public `android:` names for the same op. */
internal fun groupedAliasDebugNames(definition: AppOpDefinition): List<String> =
    definition.aliases.filterNot { ':' in it }.distinct()

@Composable
internal fun AppOpsSection(
    state: PermissionUiState,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (AppOpsFilter) -> Unit,
    onRefresh: () -> Unit,
    onSetMode: (Int, AppOpScope, AppOpMode) -> Unit,
    onResetMode: (Int, AppOpScope) -> Unit,
) {
    val snapshot = state.appOpsSnapshot
    var selectedCode by rememberSaveable(state.packageName) { mutableIntStateOf(-1) }
    var selectedScope by rememberSaveable(state.packageName) { mutableStateOf(AppOpScope.PACKAGE) }
    val selectedEntry = snapshot?.entries?.firstOrNull { it.definition.code == selectedCode }

    Column(modifier = Modifier.fillMaxSize()) {
        if (snapshot == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.isAppOpsLoading || !state.appOpsLoadFailed) {
                    CircularProgressIndicator()
                } else {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (state.appOpsStatusUncertain) R.string.app_ops_status_uncertain
                                else R.string.app_ops_load_failed,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onRefresh) {
                            Text(stringResource(R.string.refresh))
                        }
                    }
                }
            }
        } else {
            if (state.isAppOpsLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.appOpsLoadFailed) {
                AppOpsNotice(
                    message = stringResource(R.string.app_ops_refresh_failed),
                    onRefresh = if (state.isAppOpsLoading) null else onRefresh,
                )
            }
            if (!snapshot.canEdit) {
                AppOpsNotice(message = stringResource(R.string.app_ops_read_only))
            }
            Text(
                text = stringResource(R.string.app_ops_explanation),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            PermissionManagerSearchBar(
                query = state.appOpsSearchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = R.string.app_ops_search,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppOpsFilter.entries.forEach { filter ->
                        val label = when (filter) {
                            AppOpsFilter.RELEVANT -> R.string.app_ops_filter_relevant
                            AppOpsFilter.CHANGED -> R.string.app_ops_filter_changed
                            AppOpsFilter.ALL -> R.string.app_ops_filter_all
                        }
                        FilterChip(
                            selected = state.appOpsFilter == filter,
                            onClick = { onFilterChange(filter) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                TextButton(
                    onClick = onRefresh,
                    enabled = !state.isAppOpsLoading && state.savingAppOpCode == null,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.refresh))
                }
            }

            val visible = remember(snapshot.entries, state.appOpsSearchQuery, state.appOpsFilter) {
                visibleAppOps(snapshot.entries, state.appOpsSearchQuery, state.appOpsFilter)
            }
            if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (snapshot.entries.isEmpty()) R.string.app_ops_none_available
                            else R.string.app_ops_no_matching,
                        ),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    val relevant = visible.filter { it.isRelevant }
                    val other = visible.filterNot { it.isRelevant }
                    if (state.appOpsFilter == AppOpsFilter.ALL && relevant.isNotEmpty() && other.isNotEmpty()) {
                        item(key = "relevant_header") {
                            AppOpsListHeader(stringResource(R.string.app_ops_filter_relevant))
                        }
                    }
                    items(relevant, key = { "op_${it.definition.code}" }) { entry ->
                        AppOpRow(
                            entry = entry,
                            isSaving = state.savingAppOpCode == entry.definition.code,
                            onClick = {
                                selectedScope = if (entry.hasUidOverride) AppOpScope.UID else AppOpScope.PACKAGE
                                selectedCode = entry.definition.code
                            },
                        )
                    }
                    if (other.isNotEmpty()) {
                        if (relevant.isNotEmpty()) {
                            item(key = "other_header") {
                                AppOpsListHeader(stringResource(R.string.app_ops_other_operations))
                            }
                        }
                        items(other, key = { "op_${it.definition.code}" }) { entry ->
                            AppOpRow(
                                entry = entry,
                                isSaving = state.savingAppOpCode == entry.definition.code,
                                onClick = {
                                    selectedScope = if (entry.hasUidOverride) AppOpScope.UID else AppOpScope.PACKAGE
                                    selectedCode = entry.definition.code
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (snapshot != null && selectedEntry != null) {
        AppOpEditorSheet(
            entry = selectedEntry,
            snapshot = snapshot,
            packageName = state.packageName,
            scope = selectedScope,
            onScopeChange = { selectedScope = it },
            editsBlocked = state.isAppOpsLoading || state.savingAppOpCode != null ||
                state.appOpsLoadFailed || state.appOpsStatusUncertain,
            statusUncertain = state.appOpsLoadFailed || state.appOpsStatusUncertain,
            onDismiss = { selectedCode = -1 },
            onSetMode = { mode -> onSetMode(selectedEntry.definition.code, selectedScope, mode) },
            onResetMode = { onResetMode(selectedEntry.definition.code, selectedScope) },
        )
    }
}

@Composable
private fun AppOpsNotice(message: String, onRefresh: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRefresh != null) {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        }
    }
}

@Composable
private fun AppOpsListHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AppOpRow(entry: AppOpEntry, isSaving: Boolean, onClick: () -> Unit) {
    val source = when {
        entry.hasUidOverride -> stringResource(R.string.app_ops_scope_uid)
        entry.packageMode != null -> stringResource(R.string.app_ops_scope_package)
        else -> stringResource(R.string.app_ops_source_platform)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appOpDisplayName(entry.definition),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.definition.publicName ?: entry.definition.debugName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isChanged) {
                Text(
                    text = stringResource(R.string.app_ops_changed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = appOpModeLabel(entry.displayedMode),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface PendingAppOpAction {
    data class Set(val mode: AppOpMode) : PendingAppOpAction
    data object Reset : PendingAppOpAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppOpEditorSheet(
    entry: AppOpEntry,
    snapshot: AppOpsSnapshot,
    packageName: String,
    scope: AppOpScope,
    onScopeChange: (AppOpScope) -> Unit,
    editsBlocked: Boolean,
    statusUncertain: Boolean,
    onDismiss: () -> Unit,
    onSetMode: (AppOpMode) -> Unit,
    onResetMode: () -> Unit,
) {
    val currentScopeMode = (if (scope == AppOpScope.PACKAGE) entry.packageMode else entry.uidMode)
        ?: entry.definition.platformDefault
    var selectedMode by remember(entry.definition.code, scope, currentScopeMode) {
        mutableStateOf(currentScopeMode)
    }
    var pendingAction by remember(entry.definition.code, scope) {
        mutableStateOf<PendingAppOpAction?>(null)
    }
    val otherSharedPackages = snapshot.sharedUidPackages.filterNot { it == packageName }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = appOpDisplayName(entry.definition),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.app_ops_technical_name, entry.definition.debugName, entry.definition.code),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val linkedOperations = remember(entry.definition.aliases) {
                groupedAliasDebugNames(entry.definition)
            }
            if (linkedOperations.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.app_ops_related_operations,
                        linkedOperations.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.definition.relatedPermissions.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.app_ops_related_permissions,
                        entry.definition.relatedPermissions.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.app_ops_uid_user, snapshot.userId, snapshot.uid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.app_ops_platform_baseline, appOpModeLabel(entry.definition.platformDefault)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.app_ops_package_readback,
                    entry.packageMode?.let { appOpModeLabel(it) }
                        ?: stringResource(R.string.app_ops_no_override),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.app_ops_uid_readback,
                    entry.uidMode?.let { appOpModeLabel(it) }
                        ?: stringResource(R.string.app_ops_no_override),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.app_ops_edit_scope),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOpScope.entries.forEach { candidate ->
                    FilterChip(
                        selected = scope == candidate,
                        onClick = { onScopeChange(candidate) },
                        enabled = !editsBlocked,
                        label = {
                            Text(
                                stringResource(
                                    if (candidate == AppOpScope.PACKAGE) R.string.app_ops_scope_package
                                    else R.string.app_ops_scope_uid,
                                ),
                            )
                        },
                    )
                }
            }
            if (scope == AppOpScope.UID) {
                Text(
                    text = if (otherSharedPackages.isEmpty()) {
                        stringResource(R.string.app_ops_uid_warning)
                    } else {
                        stringResource(R.string.app_ops_shared_uid_warning, otherSharedPackages.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (entry.hasUidOverride) {
                Text(
                    text = stringResource(R.string.app_ops_uid_override_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!snapshot.canEdit) {
                Text(
                    text = stringResource(R.string.app_ops_read_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (statusUncertain) {
                Text(
                    text = stringResource(R.string.app_ops_refresh_before_edit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.app_ops_choose_mode),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            editableModes.forEach { mode ->
                AppOpModeOption(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = snapshot.canEdit && !editsBlocked,
                    onClick = { selectedMode = mode },
                )
            }
            Button(
                onClick = { pendingAction = PendingAppOpAction.Set(selectedMode) },
                enabled = snapshot.canEdit && !editsBlocked && selectedMode != AppOpMode.UNKNOWN &&
                    selectedMode != currentScopeMode,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.app_ops_apply_mode))
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = { pendingAction = PendingAppOpAction.Reset },
                enabled = snapshot.canEdit && !editsBlocked && entry.definition.allowsReset &&
                    entry.definition.platformDefault != AppOpMode.UNKNOWN &&
                    (if (scope == AppOpScope.PACKAGE) entry.packageMode != null else entry.uidMode != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.app_ops_reset_platform_default))
            }
            Text(
                text = stringResource(R.string.app_ops_reset_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val action = pendingAction
    if (action != null) {
        val scopeName = stringResource(
            if (scope == AppOpScope.PACKAGE) R.string.app_ops_scope_package else R.string.app_ops_scope_uid,
        )
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.app_ops_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (action) {
                            is PendingAppOpAction.Set -> stringResource(
                                R.string.app_ops_confirm_set,
                                appOpModeLabel(action.mode), scopeName, appOpDisplayName(entry.definition),
                            )
                            PendingAppOpAction.Reset -> stringResource(
                                R.string.app_ops_confirm_reset,
                                scopeName, appOpDisplayName(entry.definition),
                            )
                        },
                    )
                    if (scope == AppOpScope.UID) {
                        Text(
                            text = if (otherSharedPackages.isEmpty()) {
                                stringResource(R.string.app_ops_uid_warning)
                            } else {
                                stringResource(
                                    R.string.app_ops_shared_uid_warning,
                                    otherSharedPackages.joinToString(", "),
                                )
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        when (action) {
                            is PendingAppOpAction.Set -> onSetMode(action.mode)
                            PendingAppOpAction.Reset -> onResetMode()
                        }
                    },
                    enabled = snapshot.canEdit && !editsBlocked,
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AppOpModeOption(
    mode: AppOpMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = when (mode) {
        AppOpMode.ALLOW -> R.string.app_ops_mode_allow_desc
        AppOpMode.IGNORE -> R.string.app_ops_mode_ignore_desc
        AppOpMode.DENY -> R.string.app_ops_mode_deny_desc
        AppOpMode.DEFAULT -> R.string.app_ops_mode_default_desc
        AppOpMode.FOREGROUND -> R.string.app_ops_mode_foreground_desc
        AppOpMode.UNKNOWN -> R.string.app_ops_mode_unknown
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(appOpModeLabel(mode), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun appOpModeLabel(mode: AppOpMode): String = stringResource(
    when (mode) {
        AppOpMode.ALLOW -> R.string.app_ops_mode_allow
        AppOpMode.IGNORE -> R.string.app_ops_mode_ignore
        AppOpMode.DENY -> R.string.app_ops_mode_deny
        AppOpMode.DEFAULT -> R.string.app_ops_mode_default
        AppOpMode.FOREGROUND -> R.string.app_ops_mode_foreground
        AppOpMode.UNKNOWN -> R.string.app_ops_mode_unknown
    },
)
