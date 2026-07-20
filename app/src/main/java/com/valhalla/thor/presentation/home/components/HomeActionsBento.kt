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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

/**
 * The Home actions bento grid. Renders the rows from [homeActionRows]: a two-tile row is a
 * weighted pair (equal height via IntrinsicSize.Min); a one-tile row spans full width.
 * Shared by both HomeScreen layout branches. animateContentSize smooths reflow when a tile
 * appears/disappears (privilege resolves, reinstall dismissed).
 */
@Composable
fun HomeActionsBento(
    reinstallVisible: Boolean,
    isRoot: Boolean,
    hasPrivilege: Boolean,
    unknownInstallerCount: Int,
    selectedTypeName: String,
    onReinstall: () -> Unit,
    onDismissReinstall: () -> Unit,
    onInstall: () -> Unit,
    onClearCache: () -> Unit,
    onNavigateToExtensionManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = homeActionRows(reinstallVisible, isRoot, hasPrivilege)

    @Composable
    fun Tile(action: HomeAction, tileModifier: Modifier) {
        when (action) {
            HomeAction.REINSTALL -> BentoTile(
                title = stringResource(R.string.reinstall_all),
                subtitle = stringResource(R.string.reinstall_all_subtitle, unknownInstallerCount, selectedTypeName),
                icon = R.drawable.apk_install,
                isWarning = true,
                onClose = onDismissReinstall,
                onClick = onReinstall,
                modifier = tileModifier,
            )
            HomeAction.INSTALL -> BentoTile(
                title = stringResource(R.string.install_from_file),
                subtitle = stringResource(R.string.install_from_file_subtitle),
                icon = R.drawable.apk_install,
                isPrimary = true,
                onClick = onInstall,
                modifier = tileModifier,
            )
            HomeAction.CLEAR_CACHE -> BentoTile(
                title = stringResource(R.string.clear_all_cache),
                subtitle = stringResource(R.string.clear_all_cache_subtitle),
                icon = R.drawable.clear_all,
                onClick = onClearCache,
                modifier = tileModifier,
            )
            HomeAction.EXTENSIONS -> BentoTile(
                title = stringResource(R.string.home_extensions_title),
                subtitle = stringResource(R.string.home_extensions_subtitle),
                icon = R.drawable.round_extension,
                onClick = onNavigateToExtensionManager,
                modifier = tileModifier,
            )
        }
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
}
