// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppInfoActionId
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
 * self-contained, and the three nullable callbacks below, which are genuinely surface-specific:
 *
 * - [onOpenDetails] — meaningless on the details screen itself; pass null there.
 * - [onToggleFreezerMembership] — pass null where freezer membership isn't known. What *leaving* the
 *   freezer means is the host's to define, and the two hosts define it differently: see
 *   `docs/follow-ups/freezer-membership-toggle-semantics.md`.
 * - [onBackup] — pass null from a host that does not carry the backup sheet.
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
    @StringRes freezerRemoveLabelRes: Int = R.string.action_remove_from_watchlist,
    onOpenDetails: (() -> Unit)? = null,
    /**
     * Null hides the tile — the same convention as [onToggleFreezerMembership] and [onOpenDetails],
     * which is why it sits with them.
     *
     * Null is not the only way to get no tile: the action is additionally gated on a privilege mode
     * being active, so a non-null callback still renders nothing on a device with no shell.
     */
    onBackup: (() -> Unit)? = null
) {
    val hasPrivilege = isRoot || isShizuku || isDhizuku
    val isFrozen = !appInfo.enabled
    val isSuspended = appInfo.isSuspended

    // Self-contained launcher-shortcut action — gated on the feature setting + pin support + user app.
    val shortcutManager = koinInject<FreezerShortcutManager>()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val prefs by preferenceRepository.userPreferences.collectAsStateWithLifecycle(UserPreferences())

    // Which of the three explainers is open, if any. rememberSaveable so a rotation with the sheet
    // up does not drop the paragraph the user was reading.
    var explaining by rememberSaveable { mutableStateOf<ActionExplainer?>(null) }
    val showDetails = stringResource(R.string.show_details)

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
        val actionsToRender = prefs.appInfoActionsOrder.filterNot { it in prefs.hiddenAppInfoActions }

        actionsToRender.forEach { action ->
            when (action) {
                AppInfoActionId.OPEN -> ActionItem(
                    icon = R.drawable.open_in_new,
                    label = stringResource(R.string.action_open),
                    // Always tappable: Launch restores (unsuspends / enables) a frozen or suspended app
                    // before launching. Non-launchable apps fall through to a "can't launch" toast.
                    enabled = true,
                    onClick = onLaunch
                )

                AppInfoActionId.SETTINGS -> ActionItem(
                    icon = R.drawable.settings,
                    label = stringResource(R.string.settings),
                    onClick = onSystemSettings
                )

                AppInfoActionId.FREEZE -> if (hasPrivilege) {
                    val freezeLabel =
                        if (isFrozen) stringResource(R.string.action_unfreeze) else stringResource(R.string.action_freeze)
                    val freezeIcon = if (isFrozen) R.drawable.freeze_off else R.drawable.frozen
                    ActionItem(
                        icon = freezeIcon,
                        label = freezeLabel,
                        longPressLabel = showDetails,
                        onLongPress = { explaining = ActionExplainer.FREEZE },
                        onClick = { onFreezeToggle(!isFrozen) }
                    )
                }

                AppInfoActionId.SUSPEND -> if (hasPrivilege) {
                    val suspendLabel =
                        if (isSuspended) stringResource(R.string.action_unsuspend) else stringResource(R.string.action_suspend)
                    val suspendIcon = if (isSuspended) R.drawable.bolt else R.drawable.warning
                    ActionItem(
                        icon = suspendIcon,
                        label = suspendLabel,
                        longPressLabel = showDetails,
                        onLongPress = { explaining = ActionExplainer.SUSPEND },
                        onClick = { onSuspendToggle(!isSuspended) }
                    )
                }

                AppInfoActionId.FORCE_STOP -> if (hasPrivilege && appInfo.enabled) {
                    ActionItem(
                        icon = R.drawable.force_close,
                        label = stringResource(R.string.action_force_stop),
                        longPressLabel = showDetails,
                        onLongPress = { explaining = ActionExplainer.FORCE_STOP },
                        onClick = onForceStop
                    )
                }

                AppInfoActionId.PERMISSIONS -> ActionItem(
                    icon = R.drawable.shield,
                    label = stringResource(R.string.action_permissions),
                    onClick = onManagePermissions
                )

                AppInfoActionId.FREEZER_MEMBERSHIP -> onToggleFreezerMembership?.let { toggle ->
                    val freezerLabel =
                        if (isInFreezer) stringResource(freezerRemoveLabelRes) else stringResource(R.string.action_add_freezer)
                    ActionItem(
                        icon = R.drawable.snowflake,
                        label = freezerLabel,
                        tintColor = if (isInFreezer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        onClick = toggle
                    )
                }

                AppInfoActionId.CLEAR_CACHE -> if (isRoot) {
                    ActionItem(
                        icon = R.drawable.clear_all,
                        label = stringResource(R.string.action_clear_cache),
                        onClick = onClearCache
                    )
                }

                AppInfoActionId.CLEAR_DATA -> if (hasPrivilege) {
                    ActionItem(
                        icon = R.drawable.delete,
                        label = stringResource(R.string.action_clear_data),
                        onClick = onClearData
                    )
                }

                AppInfoActionId.SHARE -> ActionItem(
                    icon = R.drawable.share,
                    label = stringResource(R.string.action_share),
                    onClick = onShare
                )

                AppInfoActionId.EXPORT -> ActionItem(
                    icon = R.drawable.storage,
                    label = stringResource(R.string.action_export),
                    onClick = onExport
                )

                AppInfoActionId.BACKUP -> if (hasPrivilege) {
                    onBackup?.let { backup ->
                        ActionItem(
                            icon = R.drawable.settings_backup_restore,
                            label = stringResource(R.string.action_backup),
                            onClick = backup
                        )
                    }
                }

                AppInfoActionId.DETAILS -> onOpenDetails?.let { openDetails ->
                    ActionItem(
                        icon = R.drawable.list_alt,
                        label = stringResource(R.string.action_details),
                        onClick = openDetails
                    )
                }

                AppInfoActionId.ADD_TO_HOME -> if (prefs.addFreezerToLauncher && !appInfo.isSystem && shortcutManager.isPinSupported()) {
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

                AppInfoActionId.FIX_STORE -> if (hasPrivilege && !appInfo.isSystem && appInfo.installerPackageName != PLAY_STORE_PACKAGE) {
                    ActionItem(
                        icon = R.drawable.apk_install,
                        label = stringResource(R.string.fix_store),
                        onClick = onFixStore
                    )
                }

                AppInfoActionId.UNINSTALL -> if (appInfo.packageName != BuildConfig.APPLICATION_ID) {
                    ActionItem(
                        icon = R.drawable.delete_forever,
                        label = stringResource(R.string.action_uninstall),
                        onClick = onUninstall
                    )
                }
            }
        }
    }

    // Outside the Row on purpose: it scrolls horizontally, and a sheet host belongs to the screen
    // rather than to the strip of tiles.
    explaining?.let { explainer ->
        InfoBottomSheet(
            title = stringResource(explainer.title),
            body = stringResource(explainer.body),
            icon = explainer.icon,
            // No confirm button, unlike the Home bento's sheets. There the long press is the *only*
            // way a compact tile can be explained, so the sheet has to hand the action back; here
            // the tile's own tap still works and all three of these are destructive. A "Freeze"
            // button on a sheet the user opened to ask what freezing *is* would be a second
            // trigger, reached by the gesture they used to hesitate.
            onDismiss = { explaining = null }
        )
    }
}

private const val PLAY_STORE_PACKAGE = "com.android.vending"

/**
 * The three actions whose names do not say what they do.
 *
 * Force Stop, Suspend and Freeze all leave the app installed and delete nothing, and they differ
 * only in how long the effect lasts and whether the icon stays on the launcher — a distinction the
 * one-word tile labels cannot carry, and the most common question asked about Thor. Long-pressing
 * any of the three opens its entry here; the other tiles have no explainer because their labels are
 * their explanation.
 */
private enum class ActionExplainer(
    val title: Int,
    val body: Int,
    val icon: Int
) {
    FREEZE(R.string.explain_freeze_title, R.string.explain_freeze_body, R.drawable.frozen),
    SUSPEND(R.string.explain_suspend_title, R.string.explain_suspend_body, R.drawable.warning),
    FORCE_STOP(
        R.string.explain_force_stop_title,
        R.string.explain_force_stop_body,
        R.drawable.force_close
    )
}

@Composable
private fun ActionItem(
    icon: Int,
    label: String,
    enabled: Boolean = true,
    tintColor: Color? = null,
    longPressLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val longPress = onLongPress
    AsgardActionItem(
        icon = ImageVector.vectorResource(icon),
        label = label,
        onClick = onClick,
        enabled = enabled,
        iconTint = tintColor ?: MaterialTheme.colorScheme.primary,
        onLongClick = longPress,
        // AsgardActionItem takes an onLongClick but no label for it, so an assistive-technology user
        // is told a long press exists and not what it does. Declaring the action here supplies the
        // name: Asgard applies this `modifier` at the head of its chain, ahead of its own
        // combinedClickable, and when two peers set the same semantics key it is the outermost
        // non-null label and action that survive the collapse. Same handler either way, so the
        // gesture and the accessibility action stay in step.
        modifier = if (longPress != null) {
            Modifier.semantics {
                onLongClick(label = longPressLabel) {
                    longPress()
                    true
                }
            }
        } else {
            Modifier
        }
    )
}
