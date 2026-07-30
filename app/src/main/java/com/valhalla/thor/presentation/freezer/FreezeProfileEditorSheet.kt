// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.ProfileNameError
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.domain.model.profileNameError
import com.valhalla.thor.presentation.widgets.AppRiskAction
import com.valhalla.thor.presentation.widgets.AppRiskDialog
import com.valhalla.thor.presentation.widgets.AppSearchBar
import kotlinx.coroutines.launch

/**
 * Create or edit one freeze profile: a name and the apps it covers.
 *
 * [profile] null means create. [initialSelection] seeds a fresh profile from the Freezer's
 * multi-select ("save this selection as a profile"), and is ignored when editing — the profile's
 * own membership wins there.
 *
 * The sheet holds the whole edit locally and commits once, via [onSave]. Live-committing each
 * tick would publish half-built profiles to the QS-adjacent machinery that reads them, and there
 * would be no meaning for Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeProfileEditorSheet(
    profile: FreezeProfile?,
    initialSelection: Set<String>,
    existingNames: List<String>,
    allApps: List<AppInfo>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSave: (name: String, packageNames: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Keyed on the profile id so reusing this composable for a different row restarts the edit
    // rather than carrying the previous profile's name and ticks into it. The id, not the
    // profile: a save that re-emits the list must not throw away the edit in progress.
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var selection by rememberSaveable(
        profile?.id,
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(profile?.packageNames?.toSet() ?: initialSelection) }

    var selectedType by rememberSaveable { mutableStateOf(AppListType.USER) }

    // The app awaiting a blocked/expert confirmation, or null. Plain remember, as in
    // ManageFreezerSheet: AppInfo is not Saveable and a dialog surviving process death would
    // outlive the list it was raised from.
    var pendingApp by remember { mutableStateOf<AppInfo?>(null) }

    // A profile keeping its own name is not a duplicate of itself. Compared case-insensitively
    // because the unique index is NOCASE — matching it here is what keeps the inline error and
    // the database's answer from disagreeing.
    val otherNames = remember(existingNames, profile) {
        existingNames.filterNot { it.equals(profile?.name, ignoreCase = true) }
    }
    val nameError = profileNameError(name, otherNames)

    val filtered = remember(allApps, searchQuery, selectedType) {
        val typeFiltered = allApps.filter { it.isSystem == (selectedType == AppListType.SYSTEM) }
        if (searchQuery.isBlank()) typeFiltered
        else typeFiltered.filter {
            it.appName?.contains(searchQuery, ignoreCase = true) == true ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp,
        contentWindowInsets = { BottomSheetDefaults.modalWindowInsets.union(WindowInsets.ime) }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (profile == null) R.string.profile_new else R.string.profile_edit
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { onSave(name, selection.toList()) },
                    enabled = nameError == ProfileNameError.OK
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.profile_name_label)) },
                isError = name.isNotEmpty() && nameError != ProfileNameError.OK,
                supportingText = {
                    // BLANK is not shown as an error: an untouched field is not a mistake yet,
                    // and Save is already disabled. The other two are things the user has to be
                    // told, because both are silent rejections otherwise.
                    when (nameError) {
                        ProfileNameError.TOO_LONG ->
                            Text(stringResource(R.string.profile_name_too_long))

                        ProfileNameError.DUPLICATE ->
                            Text(stringResource(R.string.profile_name_duplicate))

                        else -> {}
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .onFocusChanged { if (it.hasFocus) coroutineScope.launch { sheetState.expand() } }
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.selected_count, selection.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                ConnectedButtonGroup(
                    items = AppListType.entries.map { type ->
                        ConnectedButtonGroupItem.Icon(
                            icon = ImageVector.vectorResource(
                                if (type == AppListType.USER) R.drawable.apps else R.drawable.android
                            ),
                            contentDescription = stringResource(
                                if (type == AppListType.USER) R.string.chip_user else R.string.chip_system
                            )
                        )
                    },
                    selectedIndex = AppListType.entries.indexOf(selectedType),
                    onItemSelected = { selectedType = AppListType.entries[it] },
                    modifier = Modifier.width(IntrinsicSize.Max)
                )
            }

            AppSearchBar(
                query = searchQuery,
                onQueryChange = onSearchChange,
                modifier = Modifier.onFocusChanged {
                    if (it.hasFocus) coroutineScope.launch { sheetState.expand() }
                }
            )
            Spacer(Modifier.height(4.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
        ) {
            items(filtered.sortedBy { it.appName }, key = { it.packageName }) { app ->
                val picked = app.packageName in selection
                FreezerAppPickerItem(
                    app = app,
                    selected = picked,
                    onClick = {
                        // Same rule the watchlist applies at add time, for the same reason: a
                        // profile is a standing instruction that later runs act on with no UI.
                        // The runner's tier filter would drop a blocked app from the freeze
                        // anyway, so warning here is what stops the user from building a profile
                        // that silently does less than it lists. Removal is never gated.
                        val tier = app.freezeTier
                        if (!picked && tier != FreezeTier.NORMAL) pendingApp = app
                        else selection =
                            if (picked) selection - app.packageName
                            else selection + app.packageName
                    }
                )
            }
        }
    }

    // Only non-NORMAL apps reach here, so the shared dialog's normal-tier wording is unreachable
    // from this call site: blocked gets no confirm button, expert gets the red "Freeze anyway".
    pendingApp?.let { app ->
        AppRiskDialog(
            app = app,
            action = AppRiskAction.Freeze,
            onConfirm = {
                selection = selection + app.packageName
                pendingApp = null
            },
            onDismiss = { pendingApp = null }
        )
    }
}
