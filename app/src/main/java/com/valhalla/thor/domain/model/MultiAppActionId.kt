// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.valhalla.thor.R

/** Stable customization IDs. Whether an action is available still depends on the selection. */
enum class MultiAppActionId(
    @StringRes val titleRes: Int,
    @DrawableRes val defaultIconRes: Int,
) {
    REINSTALL(R.string.action_reinstall, R.drawable.apk_install),
    FREEZE(R.string.action_freeze, R.drawable.frozen),
    UNFREEZE(R.string.action_unfreeze, R.drawable.unfreeze),
    SUSPEND(R.string.action_suspend, R.drawable.warning),
    UNSUSPEND(R.string.action_unsuspend, R.drawable.bolt),
    ADD_TO_PROFILES(R.string.profile_assignment_title, R.drawable.list_alt),
    CLEAR_CACHE(R.string.action_cache, R.drawable.clear_all),
    SHARE(R.string.action_share, R.drawable.share),
    EXPORT(R.string.action_export_selected, R.drawable.storage),
    UNINSTALL(R.string.action_uninstall, R.drawable.delete_forever),
    FORCE_STOP(R.string.action_kill, R.drawable.danger),
    SAVE_AS_PROFILE(R.string.profile_save_selection, R.drawable.list_alt),
    REMOVE_FROM_FREEZER(R.string.action_remove, R.drawable.delete);
}

/** Each toolbar has its own catalog and saved layout; Close always remains outside this catalog. */
enum class MultiAppActionLayout(val defaultOrder: List<MultiAppActionId>) {
    APP_LIST(
        listOf(
            MultiAppActionId.REINSTALL,
            MultiAppActionId.FREEZE,
            MultiAppActionId.UNFREEZE,
            MultiAppActionId.SUSPEND,
            MultiAppActionId.UNSUSPEND,
            MultiAppActionId.ADD_TO_PROFILES,
            MultiAppActionId.CLEAR_CACHE,
            MultiAppActionId.SHARE,
            MultiAppActionId.EXPORT,
            MultiAppActionId.UNINSTALL,
            MultiAppActionId.FORCE_STOP,
        )
    ),
    FREEZER(
        listOf(
            MultiAppActionId.FREEZE,
            MultiAppActionId.UNFREEZE,
            MultiAppActionId.ADD_TO_PROFILES,
            MultiAppActionId.SAVE_AS_PROFILE,
            MultiAppActionId.REMOVE_FROM_FREEZER,
            MultiAppActionId.SHARE,
            MultiAppActionId.EXPORT,
            MultiAppActionId.UNINSTALL,
        )
    );

    /** Drop unknown or other-layout IDs, preserve the chosen order, and append new actions. */
    fun fromSavedNamesOrDefault(savedNames: List<String>?): List<MultiAppActionId> {
        if (savedNames.isNullOrEmpty()) return defaultOrder
        val resolved = savedNames.mapNotNull { name ->
            defaultOrder.firstOrNull { it.name == name.trim() }
        }.distinct()
        return resolved + defaultOrder.filterNot { it in resolved }
    }

    fun fromSavedHiddenNames(savedNames: Set<String>?): Set<MultiAppActionId> =
        savedNames.orEmpty().mapNotNull { name ->
            defaultOrder.firstOrNull { it.name == name.trim() }
        }.toSet()
}
