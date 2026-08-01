// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.components.StatusChip
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.presentation.utils.getBloatRecommendationColors

/** Which of the two risky things [AppRiskDialog] is asking about. */
enum class AppRiskAction {
    /**
     * `pm disable` for a user app. For a system app, disabling it where the platform allows that
     * and removing it for the current user where it does not — `FreezePolicy.kt` owns which.
     */
    Freeze,

    /** A real uninstall. */
    Uninstall,
}

/**
 * The confirmation shown before freezing or uninstalling an app, keyed off the app's [FreezeTier].
 *
 * Every surface that can freeze or uninstall shares this one dialog, because four copies of the
 * same warning is how they drift: before this existed, the info sheet's copy had quietly dropped
 * the `isSystem` guard that its twin in the details screen still carried, and only the call-site
 * gates kept the two agreeing. The tier comes from [freezeTier], so this stays a *rendering*
 * decision — what counts as blocked lives in `FreezePolicy.kt` and nowhere else.
 *
 * A [FreezeTier.BLOCKED] app gets no confirm button at all, and on most of these paths that missing
 * button is the whole enforcement. Only `FreezerViewModel.toggleManaged` re-checks the tier before
 * acting; the single-app freeze calls behind this dialog — `AppListViewModel.freezeApp`,
 * `FreezerViewModel.freezeSingleApp`, `AppInfoDetailsViewModel.toggleFreezerState` — take the
 * package name and freeze it. So treat the blocked branch here as load-bearing, not as advice with
 * a backstop underneath it. See docs/follow-ups/single-app-freeze-tier-gate.md.
 *
 * [AppRiskAction.Freeze] is only ever confirmed for system apps — freezing a user app is a
 * reversible `pm disable` with nothing to warn about, so every caller acts on it directly without
 * asking. The freeze wording therefore only covers the system case, and what it warns about is the
 * device, not the mechanic: `freeze_system_app_desc` is about reboot loops and broken services, so
 * it stays correct now that a system freeze usually disables the package and keeps its data.
 *
 * [onConfirm] owns the action *and* the dismissal: the dialog does not close itself, so callers
 * can uninstall by intent or by privileged command, and can close the surface underneath, as each
 * one needs to.
 */
@Composable
fun AppRiskDialog(
    app: AppInfo,
    action: AppRiskAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tier = app.freezeTier
    val isBlocked = tier == FreezeTier.BLOCKED
    val isExpert = tier == FreezeTier.EXPERT
    val isSystem = app.isSystem
    val isUninstall = action == AppRiskAction.Uninstall
    // Uninstalling a *user* app is the one path with no system-stability angle at all: it gets
    // plain wording and a red confirm, rather than the UAD tier treatment.
    val isUserUninstall = isUninstall && !isSystem
    // Blocked splits two ways for the body text only: no usable UAD data at all vs. an app the
    // list names as unsafe. The verdict is identical either way.
    val isUadFailed = isSystem && app.isUadLoadFailed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    when {
                        isBlocked ->
                            if (isUninstall) R.string.uninstall_blocked
                            else R.string.freeze_blocked

                        isExpert ->
                            if (isUninstall) R.string.uninstall_expert_warning
                            else R.string.freeze_expert_warning

                        !isUninstall -> R.string.freeze_system_app_title
                        isSystem -> R.string.uninstall_system_app_title
                        else -> R.string.uninstall_app_title
                    }
                ),
                color = if (isBlocked || isExpert) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isSystem && !isUadFailed) {
                    app.bloatRecommendation?.let { rec ->
                        val (color, textColor) = getBloatRecommendationColors(rec)
                        StatusChip(text = rec, containerColor = color, contentColor = textColor)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
                Text(
                    text = when {
                        isUadFailed -> stringResource(
                            if (isUninstall) R.string.uad_load_failed_desc
                            else R.string.uad_load_failed_freeze_desc
                        )

                        isBlocked -> stringResource(
                            if (isUninstall) R.string.warning_unsafe_uninstall
                            else R.string.freeze_unsafe_desc
                        )

                        isExpert -> stringResource(
                            if (isUninstall) R.string.warning_expert_uninstall
                            else R.string.freeze_expert_desc
                        )

                        !isUninstall -> stringResource(R.string.freeze_system_app_desc)
                        isSystem -> stringResource(R.string.uninstall_system_app_desc)
                        else -> stringResource(
                            R.string.uninstall_app_desc,
                            app.appName ?: app.packageName
                        )
                    },
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            if (!isBlocked) {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = stringResource(
                            when {
                                isExpert ->
                                    if (isUninstall) R.string.uninstall_anyway
                                    else R.string.freeze_anyway

                                isUserUninstall -> R.string.action_uninstall
                                else -> R.string.yes
                            }
                        ),
                        color = if (isExpert || isUserUninstall) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        when {
                            isBlocked -> R.string.close
                            isUserUninstall -> R.string.cancel
                            else -> R.string.no
                        }
                    )
                )
            }
        }
    )
}
