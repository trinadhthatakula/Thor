// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType

/** Single source by default; combined lists opt into independent source toggles. */
@Composable
internal fun AppTypeFilter(
    selection: AppListType,
    onSelectionChanged: (AppListType) -> Unit,
    modifier: Modifier = Modifier,
    multiSelectEnabled: Boolean = false,
) {
    val items = AppListType.individualTypes.map { type ->
        ConnectedButtonGroupItem.Icon(
            icon = ImageVector.vectorResource(
                if (type == AppListType.USER) R.drawable.person else R.drawable.android
            ),
            contentDescription = stringResource(
                if (type == AppListType.USER) R.string.chip_user else R.string.chip_system
            ),
        )
    }
    if (!multiSelectEnabled) {
        ConnectedButtonGroup(
            items = items,
            selectedIndex = AppListType.individualTypes.indexOf(selection.forCombinedLists(false)),
            onItemSelected = { onSelectionChanged(AppListType.individualTypes[it]) },
            modifier = modifier.width(IntrinsicSize.Max),
        )
        return
    }
    ConnectedButtonGroup(
        items = items,
        selectedIndices = buildSet {
            if (selection.includesUser) add(0)
            if (selection.includesSystem) add(1)
        },
        onSelectionChanged = { indices ->
            // Keep the final selection fully colored, matching Home's connected buttons.
            val next = when {
                0 in indices && 1 in indices -> AppListType.ALL
                0 in indices -> AppListType.USER
                1 in indices -> AppListType.SYSTEM
                else -> selection
            }
            if (next != selection) onSelectionChanged(next)
        },
        modifier = modifier.width(IntrinsicSize.Max),
    )
}
