// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.presentation.widgets.InfoBottomSheet

/** Title, description and icon for one bento tile. */
private data class HomeActionCopy(val title: String, val subtitle: String, val icon: Int)

/**
 * The Home actions bento grid. Renders the rows from [homeActionRows]: a two-tile row is a
 * weighted pair (equal height via IntrinsicSize.Min); a one-tile row spans full width.
 * Shared by both HomeScreen layout branches. animateContentSize smooths reflow when a tile
 * appears/disappears (privilege resolves, reinstall dismissed).
 *
 * Paired tiles are too narrow for a description, so they drop it and long-press opens
 * [InfoBottomSheet] instead. Full-width tiles keep theirs and long-press is a no-op there.
 *
 * [showInstaller] and [showExtensions] are the persisted GH#344 preferences — see [homeActionRows].
 * With both off and no privilege there is nothing left to show, and this composable then emits
 * nothing at all.
 *
 * [narrowContainer] is for a pane too narrow to pair tiles at all — the wide-screen rail, where
 * the grid gets roughly a third of a 600 dp window. Every tile then goes full-width *and* compact:
 * measured across five locales and three font scales, that is the only combination in that pane
 * where nothing clips.
 */
@Composable
fun HomeActionsBento(
    reinstallVisible: Boolean,
    canClearCache: Boolean,
    hasPrivilege: Boolean,
    unknownInstallerCount: Int,
    selectedTypeName: String,
    onReinstall: () -> Unit,
    onDismissReinstall: () -> Unit,
    onInstall: () -> Unit,
    onClearCache: () -> Unit,
    onNavigateToExtensionManager: () -> Unit,
    onNavigateToBackupRestoreHub: () -> Unit,
    modifier: Modifier = Modifier,
    showInstaller: Boolean = true,
    showExtensions: Boolean = true,
    showBackupRestore: Boolean = true,
    narrowContainer: Boolean = false,
) {
    val rows = homeActionRows(
        reinstallVisible,
        canClearCache,
        hasPrivilege,
        showInstaller,
        showExtensions,
        showBackupRestore,
        narrowContainer,
    )
    var explaining by rememberSaveable { mutableStateOf<HomeAction?>(null) }
    // Hiding both optional tiles with no privilege leaves nothing to draw. Emit no Column at all
    // rather than an empty one — the Column carries no padding of its own, but its callers stack
    // spacers around it, and a bare 36 dp gap reads as a rendering bug.
    if (rows.isEmpty()) return

    @Composable
    fun copyFor(action: HomeAction) = when (action) {
        HomeAction.REINSTALL -> HomeActionCopy(
            title = stringResource(R.string.reinstall_all),
            subtitle = stringResource(
                R.string.reinstall_all_subtitle, unknownInstallerCount, selectedTypeName
            ),
            icon = R.drawable.apk_install,
        )
        HomeAction.INSTALL -> HomeActionCopy(
            title = stringResource(R.string.install_from_file),
            subtitle = stringResource(R.string.install_from_file_subtitle),
            icon = R.drawable.apk_install,
        )
        HomeAction.BACKUP_RESTORE -> HomeActionCopy(
            title = stringResource(R.string.backup_and_restore),
            subtitle = stringResource(R.string.home_backup_restore_subtitle),
            icon = R.drawable.settings_backup_restore,
        )
        HomeAction.CLEAR_CACHE -> HomeActionCopy(
            title = stringResource(R.string.clear_all_cache),
            subtitle = stringResource(R.string.clear_all_cache_subtitle),
            icon = R.drawable.clear_all,
        )
        HomeAction.EXTENSIONS -> HomeActionCopy(
            title = stringResource(R.string.home_extensions_title),
            // Not home_extensions_subtitle: "Manage & open" is a label, not an explanation.
            subtitle = stringResource(R.string.manage_extensions_desc),
            icon = R.drawable.round_extension,
        )
    }

    fun onClick(action: HomeAction) = when (action) {
        HomeAction.REINSTALL -> onReinstall
        HomeAction.INSTALL -> onInstall
        HomeAction.BACKUP_RESTORE -> onNavigateToBackupRestoreHub
        HomeAction.CLEAR_CACHE -> onClearCache
        HomeAction.EXTENSIONS -> onNavigateToExtensionManager
    }

    @Composable
    fun Tile(action: HomeAction, tileModifier: Modifier) {
        // A full-width tile shows its description already, so there is nothing for the sheet to
        // add — except in the rail, where full-width tiles are compact too.
        val showSubtitle = !narrowContainer && rows.first { action in it }.size == 1
        val copy = copyFor(action)
        BentoTile(
            title = copy.title,
            subtitle = copy.subtitle,
            icon = copy.icon,
            isPrimary = action == HomeAction.INSTALL,
            isWarning = action == HomeAction.REINSTALL,
            showSubtitle = showSubtitle,
            onLongClick = if (showSubtitle) null else ({ explaining = action }),
            onLongClickLabel = if (showSubtitle) null else stringResource(R.string.show_details),
            onClose = if (action == HomeAction.REINSTALL) onDismissReinstall else null,
            onClick = onClick(action),
            modifier = tileModifier,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            if (row.size == 1) {
                Tile(row[0], Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { action -> Tile(action, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }

    explaining?.let { action ->
        val copy = copyFor(action)
        InfoBottomSheet(
            title = copy.title,
            body = copy.subtitle,
            icon = copy.icon,
            confirmLabel = copy.title,
            onConfirm = {
                explaining = null
                onClick(action)()
            },
            onDismiss = { explaining = null },
        )
    }
}
