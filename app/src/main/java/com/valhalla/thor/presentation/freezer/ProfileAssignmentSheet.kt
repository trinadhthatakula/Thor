// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.valhalla.thor.R
import com.valhalla.thor.presentation.common.OperationMessage
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.util.UiText
import androidx.compose.ui.res.stringResource

/** The caller owns its selection and clears it only after this host reports a durable save. */
@Composable
fun ProfileAssignmentHost(
    viewModel: ProfileAssignmentViewModel,
    onAssigned: (OperationMessage) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        // A failed observation hides the entry point, so recovery cannot rely on the sheet's
        // retry button alone. Try again when this destination becomes active.
        if (viewModel.uiState.value.profilesLoadFailed) viewModel.retryProfiles()
    }
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ProfileAssignmentEvent.Assigned -> {
                val lines = if (event.result.addedCount == 0 && event.skippedCount == 0) {
                    listOf(resources.getString(R.string.profile_assignment_all_present))
                } else {
                    event.result.profiles.map { profile ->
                        resources.getString(
                            R.string.profile_assignment_result,
                            profile.profileName,
                            profile.addedCount,
                            profile.alreadyPresentCount,
                        )
                    }
                }
                val skipped = if (event.skippedCount > 0) {
                    listOf(resources.getString(R.string.profile_assignment_skipped, event.skippedCount))
                } else emptyList()
                onAssigned(
                    OperationMessage(
                        UiText.DynamicString((lines + skipped).joinToString("\n")),
                        isSuccess = event.result.addedCount > 0 && event.skippedCount == 0,
                    )
                )
            }
        }
    }
    if (state.isOpen) {
        ProfileAssignmentSheet(
            state = state,
            onToggleProfile = viewModel::toggleProfile,
            onSubmit = { viewModel.submit() },
            onDismiss = viewModel::dismiss,
            onRetry = viewModel::retryProfiles,
            onConfirmExperts = { viewModel.submit(confirmExperts = true) },
            onDismissExperts = viewModel::dismissExpertConfirmation,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileAssignmentSheet(
    state: ProfileAssignmentUiState,
    onToggleProfile: (Long) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onConfirmExperts: () -> Unit,
    onDismissExperts: () -> Unit,
) {
    val selectedPackages = remember(state.selectedApps) {
        state.selectedApps.mapTo(mutableSetOf()) { it.packageName }
    }
    val isSaving by rememberUpdatedState(state.isSaving)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { it != SheetValue.Hidden || !isSaving },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        sheetState = sheetState,
    ) {
        // A single scrolling column keeps the confirmation reachable on short windows and at
        // large font sizes, without letting a long profile list measure the sheet unbounded.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("profile_assignment_sheet"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.profile_assignment_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.profile_assignment_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.profile_assignment_selected_apps, selectedPackages.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (state.profiles.isEmpty() && !state.profilesLoadFailed) {
                item { Text(stringResource(R.string.profile_assignment_no_profiles)) }
            }
            items(state.profiles, key = { it.id }) { profile ->
                val alreadyPresent = profile.packageNames.count { it in selectedPackages }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .testTag("profile_assignment_profile_${profile.id}")
                        .toggleable(
                            value = profile.id in state.selectedProfileIds,
                            enabled = !state.isSaving && !state.profilesLoadFailed,
                            role = Role.Checkbox,
                            onValueChange = { onToggleProfile(profile.id) },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        checked = profile.id in state.selectedProfileIds,
                        onCheckedChange = null,
                        enabled = !state.isSaving && !state.profilesLoadFailed,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.profile_assignment_member_count, profile.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(
                                R.string.profile_assignment_overlap,
                                selectedPackages.size - alreadyPresent,
                                alreadyPresent,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        error.asString(LocalContext.current),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("profile_assignment_error"),
                    )
                    if (state.profilesLoadFailed) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_label)) }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.profile_assignment_selected_profiles, state.selectedProfileIds.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                Button(
                    onClick = onSubmit,
                    enabled = state.selectedProfileIds.isNotEmpty() && selectedPackages.isNotEmpty() &&
                        !state.isSaving && !state.profilesLoadFailed,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .testTag("profile_assignment_submit"),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.profile_assignment_confirm))
                }
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("profile_assignment_cancel"),
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
    if (state.expertApps.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissExperts,
            title = { Text(stringResource(R.string.profile_assignment_expert_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.profile_assignment_expert_message))
                    Text(
                        state.expertApps.joinToString("\n") { it.appName ?: it.packageName },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (state.skippedCount > 0) {
                        Text(stringResource(R.string.profile_assignment_skipped, state.skippedCount))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmExperts) {
                    Text(stringResource(R.string.profile_assignment_expert_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissExperts) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
