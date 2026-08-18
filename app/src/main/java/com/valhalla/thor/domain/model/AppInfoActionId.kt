// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.valhalla.thor.R

/**
 * The catalog of individual actions that can appear in the AppInfo bottom sheet action row.
 *
 * Each entry carries its default icon, user-facing title, and description resource.
 * The order of enum entries represents the default rendering order in [com.valhalla.thor.presentation.widgets.AppActionRow].
 */
enum class AppInfoActionId(
    @StringRes val titleRes: Int,
    @DrawableRes val defaultIconRes: Int,
    @StringRes val descriptionRes: Int,
) {
    OPEN(R.string.action_open, R.drawable.open_in_new, R.string.action_open_desc),
    SETTINGS(R.string.settings, R.drawable.settings, R.string.action_settings_desc),
    FREEZE(R.string.action_freeze, R.drawable.frozen, R.string.action_freeze_desc),
    SUSPEND(R.string.action_suspend, R.drawable.bolt, R.string.action_suspend_desc),
    FORCE_STOP(R.string.action_force_stop, R.drawable.force_close, R.string.action_force_stop_desc),
    PERMISSIONS(R.string.action_permissions, R.drawable.shield, R.string.action_permissions_desc),
    FREEZER_MEMBERSHIP(R.string.action_add_freezer, R.drawable.snowflake, R.string.action_freezer_membership_desc),
    CLEAR_CACHE(R.string.action_clear_cache, R.drawable.clear_all, R.string.action_clear_cache_desc),
    CLEAR_DATA(R.string.action_clear_data, R.drawable.delete, R.string.action_clear_data_desc),
    SHARE(R.string.action_share, R.drawable.share, R.string.action_share_desc),
    EXPORT(R.string.action_export, R.drawable.storage, R.string.action_export_desc),
    BACKUP(R.string.action_backup, R.drawable.settings_backup_restore, R.string.action_backup_desc),
    DETAILS(R.string.action_details, R.drawable.list_alt, R.string.action_details_desc),
    ADD_TO_HOME(R.string.add_to_home_screen, R.drawable.home, R.string.action_add_to_home_desc),
    FIX_STORE(R.string.fix_store, R.drawable.apk_install, R.string.fix_store_desc),
    UNINSTALL(R.string.action_uninstall, R.drawable.delete_forever, R.string.action_uninstall_desc);

    companion object {
        val DEFAULT_ORDER: List<AppInfoActionId> = entries

        /**
         * Reconciles a persisted list of action names into a valid [AppInfoActionId] list.
         * Unknown names from future or obsolete builds are dropped, and any newly added enum
         * entries missing from the persisted list are appended at the end in their default order.
         */
        fun fromSavedNamesOrDefault(savedNames: List<String>?): List<AppInfoActionId> {
            if (savedNames.isNullOrEmpty()) return DEFAULT_ORDER
            val resolved = savedNames.mapNotNull { name ->
                entries.firstOrNull { it.name == name }
            }.distinct()

            val missing = entries.filterNot { it in resolved }
            return resolved + missing
        }

        /**
         * Reconciles a persisted set of hidden action names into a set of [AppInfoActionId].
         */
        fun fromSavedHiddenNames(savedNames: Set<String>?): Set<AppInfoActionId> {
            if (savedNames.isNullOrEmpty()) return emptySet()
            return savedNames.mapNotNull { name ->
                entries.firstOrNull { it.name == name }
            }.toSet()
        }
    }
}
