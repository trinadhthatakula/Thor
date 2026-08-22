// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.presentation.widgets.AppIcon
import com.valhalla.thor.presentation.widgets.installerLabel

/**
 * The Fix Store picker.
 *
 * Fix Store used to run straight off a warning dialog that named no app, so the only way to find
 * out what it had touched was to read the log as it scrolled past. This lists the apps first, with
 * the installer each one currently records, and lets any of them be unticked.
 *
 * [labelFor] is the installer-name lookup; it is passed in rather than injected so this composable
 * has no dependency on a `PackageManager` and the naming stays the one shared rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixStoreSheet(
    selection: FixStoreSelection,
    labelFor: (String) -> String?,
    onToggle: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Resolved once per distinct installer, not once per row: the lookup crosses into
    // PackageManager, and a list of 80 sideloaded apps usually names two or three stores between
    // them.
    val installerLabels = remember(selection.candidates) {
        selection.candidates
            .mapNotNull { it.recordedInstaller() }
            .distinct()
            .associateWith { installerLabel(it, labelFor) }
    }

    val selectedCount = selection.selected.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp,
        contentWindowInsets = { BottomSheetDefaults.modalWindowInsets }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.fix_store),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Text(
                text = stringResource(R.string.fix_store_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    painter = painterResource(R.drawable.danger),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.fix_store_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onSetAll(true) }) {
                    Text(stringResource(R.string.select_all))
                }
                TextButton(onClick = { onSetAll(false) }) {
                    Text(stringResource(R.string.select_none))
                }
            }

            // Bounded so the confirm button below is always on screen. The sheet expands to fit its
            // content otherwise, and a 200-app list would push the only way out past the bottom.
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(selection.candidates, key = { it.packageName }) { app ->
                    FixStoreRow(
                        app = app,
                        isSelected = app.packageName in selection.selected,
                        installerLabel = app.recordedInstaller()
                            ?.let { installerLabels[it]?.asString() },
                        onToggle = { onToggle(app.packageName) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = selectedCount > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.fix_store_confirm,
                            selectedCount,
                            selectedCount
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FixStoreRow(
    app: AppInfo,
    isSelected: Boolean,
    installerLabel: String?,
    onToggle: () -> Unit
) {
    Row(
        // `toggleable` rather than `clickable` + a live checkbox: the two together are one toggle
        // but two semantics nodes, so a screen reader gets an unlabelled container and a checkbox
        // that names nothing. This merges them, and `Role.Checkbox` is what makes the state part
        // of the announcement instead of "double tap to activate".
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = isSelected,
                onValueChange = { onToggle() },
                role = Role.Checkbox
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Null: the row above owns the click. A live handler here would be the second node again.
        Checkbox(checked = isSelected, onCheckedChange = null)

        AppIcon(
            packageName = app.packageName,
            isEnabled = app.enabled,
            isSuspended = app.isSuspended,
            size = 36.dp
        )

        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = app.appName ?: app.packageName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (installerLabel == null) {
                    stringResource(R.string.fix_store_installer_none)
                } else {
                    stringResource(R.string.fix_store_installed_by, installerLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The installer Android actually recorded, or null.
 *
 * Null, blank and [Installers.UNKNOWN] are the same fact arriving from different layers, and this
 * screen has to say the same thing about all three.
 */
private fun AppInfo.recordedInstaller(): String? =
    installerPackageName?.takeUnless { it == Installers.UNKNOWN || it.isBlank() }
