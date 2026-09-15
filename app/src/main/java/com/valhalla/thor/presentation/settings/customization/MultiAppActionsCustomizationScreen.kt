// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.MultiAppActionId
import com.valhalla.thor.domain.model.MultiAppActionLayout
import com.valhalla.thor.presentation.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun MultiAppActionsCustomizationScreen(
    layout: MultiAppActionLayout,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MultiAppActionsCustomizationContent(
        layout = layout,
        currentOrder = state.prefs.multiAppActionsOrder(layout),
        hiddenActions = state.prefs.hiddenMultiAppActions(layout),
        onBack = onBack,
        onOrderChanged = viewModel::setMultiAppActionsOrder,
        onVisibilityChanged = viewModel::setMultiAppActionVisibility,
        onReset = viewModel::resetMultiAppActionsCustomization,
    )
}

@Composable
internal fun MultiAppActionsCustomizationContent(
    layout: MultiAppActionLayout,
    currentOrder: List<MultiAppActionId>,
    hiddenActions: Set<MultiAppActionId>,
    onBack: () -> Unit,
    onOrderChanged: (MultiAppActionLayout, List<MultiAppActionId>) -> Unit,
    onVisibilityChanged: (MultiAppActionLayout, MultiAppActionId, Boolean) -> Unit,
    onReset: (MultiAppActionLayout) -> Unit,
) {
    // Independent navigation entries must not share a local drag snapshot or reset dialog.
    key(layout) {
        ActionsCustomizationEditor(
            currentOrder = currentOrder.map { action ->
                CustomizationAction(
                    id = action,
                    key = action.name,
                    titleRes = action.titleRes,
                    defaultIconRes = action.defaultIconRes,
                )
            },
            hiddenActions = hiddenActions,
            titleRes = when (layout) {
                MultiAppActionLayout.APP_LIST -> R.string.customization_app_list_multi_actions
                MultiAppActionLayout.FREEZER -> R.string.customization_freezer_multi_actions
            },
            onBack = onBack,
            onOrderChanged = { onOrderChanged(layout, it) },
            onVisibilityChanged = { action, visible ->
                onVisibilityChanged(layout, action, visible)
            },
            onReset = { onReset(layout) },
            fixedPreviewActions = listOf(
                CustomizationPreviewAction(R.string.close, R.drawable.round_close)
            ),
            availabilityRes = R.string.customization_multi_actions_availability,
            resetDescriptionRes = R.string.reset_multi_actions_confirm_desc,
        )
    }
}
