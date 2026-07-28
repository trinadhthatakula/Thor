// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import org.koin.compose.koinInject

/**
 * The single horizontal strip of per-app actions.
 *
 * There used to be two of these — one in the info bottom sheet, one in the details screen — with
 * different labels, different icons and different contents for the same operations. Fix Store was
 * only in one of them and the Freezer toggle only in the other, so which actions an app offered
 * depended on which surface you happened to reach it from.
 *
 * Every action is a callback: this composable decides *whether an action is applicable* (privilege,
 * system-app status, installer, feature settings), the caller decides what it does and whether it
 * needs a confirmation first. The two exceptions are the launcher-pin action, which is entirely
 * self-contained, and the two nullable callbacks below, which are genuinely surface-specific:
 *
 * - [onOpenDetails] — meaningless on the details screen itself; pass null there.
 * - [onToggleFreezerMembership] — pass null where freezer membership isn't known.
 *
 * A null callback hides its action rather than disabling it; an action that can never do anything
 * useful here is noise, not a hint.
 */
@Composable
fun AppActionRow(
    appInfo: AppInfo,
    isRoot: Boolean,
    isShizuku: Boolean,
    isDhizuku: Boolean,
    onLaunch: () -> Unit,
    onSystemSettings: () -> Unit,
    onFreezeToggle: (Boolean) -> Unit,
    onSuspendToggle: (Boolean) -> Unit,
    onForceStop: () -> Unit,
    onManagePermissions: () -> Unit,
    onClearCache: () -> Unit,
    onClearData: () -> Unit,
    onFixStore: () -> Unit,
    onUninstall: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    isInFreezer: Boolean = false,
    onToggleFreezerMembership: (() -> Unit)? = null,
    onOpenDetails: (() -> Unit)? = null
) {
    val hasPrivilege = isRoot || isShizuku || isDhizuku
    val isFrozen = !appInfo.enabled
    val isSuspended = appInfo.isSuspended

    // Self-contained launcher-shortcut action — gated on the feature setting + pin support + user app.
    val shortcutManager = koinInject<FreezerShortcutManager>()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val prefs by preferenceRepository.userPreferences.collectAsStateWithLifecycle(UserPreferences())

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        // Start-aligned rather than centred: the row gains and loses items as privilege and
        // installer state change, and centring would slide every icon sideways when it does.
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        ActionItem(
            icon = R.drawable.open_in_new,
            label = stringResource(R.string.action_open),
            // Always tappable: Launch restores (unsuspends / enables) a frozen or suspended app
            // before launching. Non-launchable apps fall through to a "can't launch" toast.
            enabled = true,
            onClick = onLaunch
        )

        ActionItem(
            icon = R.drawable.settings,
            label = stringResource(R.string.settings),
            onClick = onSystemSettings
        )

        if (hasPrivilege) {
            val freezeLabel =
                if (isFrozen) stringResource(R.string.action_unfreeze) else stringResource(R.string.action_freeze)
            val freezeIcon = if (isFrozen) R.drawable.freeze_off else R.drawable.frozen
            ActionItem(
                icon = freezeIcon,
                label = freezeLabel,
                onClick = { onFreezeToggle(!isFrozen) }
            )

            val suspendLabel =
                if (isSuspended) stringResource(R.string.action_unsuspend) else stringResource(R.string.action_suspend)
            val suspendIcon = if (isSuspended) R.drawable.bolt else R.drawable.warning
            ActionItem(
                icon = suspendIcon,
                label = suspendLabel,
                onClick = { onSuspendToggle(!isSuspended) }
            )

            if (appInfo.enabled) {
                ActionItem(
                    icon = R.drawable.force_close,
                    label = stringResource(R.string.action_force_stop),
                    onClick = onForceStop
                )
            }
        }

        ActionItem(
            icon = R.drawable.shield,
            label = stringResource(R.string.action_permissions),
            onClick = onManagePermissions
        )

        onToggleFreezerMembership?.let { toggle ->
            val freezerLabel =
                if (isInFreezer) stringResource(R.string.action_in_freezer) else stringResource(R.string.action_add_freezer)
            ActionItem(
                icon = R.drawable.snowflake,
                label = freezerLabel,
                tintColor = if (isInFreezer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                onClick = toggle
            )
        }

        if (hasPrivilege) {
            ActionItem(
                icon = R.drawable.clear_all,
                label = stringResource(R.string.action_clear_cache),
                onClick = onClearCache
            )
            ActionItem(
                icon = R.drawable.delete,
                label = stringResource(R.string.action_clear_data),
                onClick = onClearData
            )
        }

        ActionItem(
            icon = R.drawable.share,
            label = stringResource(R.string.action_share),
            onClick = onShare
        )

        ActionItem(
            icon = R.drawable.storage,
            label = stringResource(R.string.action_export),
            onClick = onExport
        )

        onOpenDetails?.let { openDetails ->
            ActionItem(
                icon = R.drawable.list_alt,
                label = stringResource(R.string.action_details),
                onClick = openDetails
            )
        }

        if (prefs.addFreezerToLauncher && !appInfo.isSystem && shortcutManager.isPinSupported()) {
            ActionItem(
                icon = R.drawable.home,
                label = stringResource(R.string.add_to_home_screen),
                onClick = {
                    shortcutManager.pinAppShortcut(
                        appInfo.packageName,
                        appInfo.appName ?: appInfo.packageName
                    )
                }
            )
        }

        // Re-point a sideloaded app at the Play Store so it can be updated normally. Pointless for
        // system apps and for anything Play already owns.
        if (hasPrivilege && !appInfo.isSystem && appInfo.installerPackageName != PLAY_STORE_PACKAGE) {
            ActionItem(
                icon = R.drawable.apk_install,
                label = stringResource(R.string.fix_store),
                onClick = onFixStore
            )
        }

        if (appInfo.packageName != BuildConfig.APPLICATION_ID) {
            ActionItem(
                icon = R.drawable.delete_forever,
                label = stringResource(R.string.action_uninstall),
                onClick = onUninstall
            )
        }
    }
}

private const val PLAY_STORE_PACKAGE = "com.android.vending"

@Composable
private fun ActionItem(
    icon: Int,
    label: String,
    enabled: Boolean = true,
    tintColor: Color? = null,
    onClick: () -> Unit
) {
    AsgardActionItem(
        icon = ImageVector.vectorResource(icon),
        label = label,
        onClick = onClick,
        enabled = enabled,
        iconTint = tintColor ?: MaterialTheme.colorScheme.primary,
    )
}
