// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.presentation.widgets.MultiActionItem
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.MultiAppActionId
import com.valhalla.thor.domain.model.MultiAppActionLayout
import androidx.compose.ui.platform.testTag
import com.valhalla.thor.domain.model.isActive
import com.valhalla.thor.domain.model.isFrozen

@Composable
fun FreezerSelectToolBox(
    modifier: Modifier = Modifier,
    selected: List<AppInfo>,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    isDhizuku: Boolean = false,
    onCancel: () -> Unit = {},
    onRemoveFromFreezer: () -> Unit = {},
    onSaveAsProfile: () -> Unit = {},
    onAddToProfiles: (() -> Unit)? = null,
    onMultiAppAction: (MultiAppAction) -> Unit = {},
    freezerMode: FreezerMode = FreezerMode.FREEZE,
    actionOrder: List<MultiAppActionId> = MultiAppActionLayout.FREEZER.defaultOrder,
    hiddenActions: Set<MultiAppActionId> = emptySet(),
) {
    // "Frozen" = disabled OR suspended; "active" = freezable (enabled & not suspended). GH#239.
    val hasFrozen = remember(selected) { selected.any { it.isFrozen } }
    val hasUnFrozen = remember(selected) { selected.any { it.isActive } }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MultiActionItem(
                icon = R.drawable.round_close,
                label = stringResource(R.string.close),
                onClick = onCancel,
                modifier = Modifier.testTag("freezer_multi_action_close"),
            )

            val hasPrivilege = isRoot || isShizuku || isDhizuku
            actionOrder.distinct().filterNot { it in hiddenActions }.forEach { action ->
                val available = when (action) {
                    MultiAppActionId.FREEZE -> hasPrivilege && hasUnFrozen
                    MultiAppActionId.UNFREEZE -> hasPrivilege && hasFrozen
                    MultiAppActionId.ADD_TO_PROFILES -> onAddToProfiles != null
                    MultiAppActionId.SAVE_AS_PROFILE, MultiAppActionId.REMOVE_FROM_FREEZER,
                    MultiAppActionId.SHARE, MultiAppActionId.EXPORT, MultiAppActionId.UNINSTALL -> true
                    else -> false
                }
                if (available) {
                    MultiActionItem(
                        icon = action.defaultIconRes,
                        label = stringResource(action.titleRes),
                        modifier = Modifier.testTag("freezer_multi_action_${action.name}"),
                        onClick = {
                            when (action) {
                                MultiAppActionId.FREEZE -> onMultiAppAction(
                                    MultiAppAction.Freeze(selected, useSuspend = freezerMode == FreezerMode.SUSPEND)
                                )
                                MultiAppActionId.UNFREEZE -> onMultiAppAction(MultiAppAction.UnFreeze(selected))
                                MultiAppActionId.ADD_TO_PROFILES -> onAddToProfiles?.invoke()
                                MultiAppActionId.SAVE_AS_PROFILE -> onSaveAsProfile()
                                MultiAppActionId.REMOVE_FROM_FREEZER -> onRemoveFromFreezer()
                                MultiAppActionId.SHARE -> onMultiAppAction(MultiAppAction.Share(selected))
                                MultiAppActionId.EXPORT -> onMultiAppAction(MultiAppAction.Backup(selected))
                                MultiAppActionId.UNINSTALL -> onMultiAppAction(MultiAppAction.Uninstall(selected))
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }
    }
}
