// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface ThorRoute : NavKey {

    @Serializable
    data object Home : ThorRoute

    @Serializable
    data object Apps : ThorRoute

    @Serializable
    data object Freezer : ThorRoute

    @Serializable
    data object Settings : ThorRoute

    /**
     * One of the eight settings categories, opened from the index.
     *
     * Deliberately **one parameterised route rather than eight `data object`s.**
     * [androidx.navigation3.runtime.rememberNavBackStack] persists the stack for task restoration,
     * and that stack outlives an app update: a user backgrounded on `SettingsCategory("freezer")`
     * can come back to a build where Freezer settings were renamed, merged, or removed. With eight
     * objects the restore is a `Class.forName` on a type this build no longer has, which takes the
     * *whole* back stack down, not just that entry. With an id string, an unknown category is a
     * `fromId` returning null and one entry popping itself.
     *
     * @param id [com.valhalla.thor.presentation.settings.SettingsCategory.id]. A string, not the
     *   ordinal: an ordinal survives the deserialize and then resolves to a *different* category the
     *   first time that enum is reordered, which is worse than failing.
     * @param focus the `name` of a [com.valhalla.thor.presentation.settings.SettingsRowId] to scroll
     *   to and light up, set when the user arrived from a search result rather than by tapping the
     *   category. Same reasoning as [id] for why it is a string; an unresolvable one is simply no
     *   focus.
     */
    @Serializable
    data class SettingsCategory(val id: String, val focus: String? = null) : ThorRoute

    @Serializable
    data class PermissionManager(val packageName: String, val appName: String) : ThorRoute

    @Serializable
    data class AppInfoDetails(val packageName: String, val appName: String) : ThorRoute

    @Serializable
    data object ExtensionManager : ThorRoute

    @Serializable
    data object ExtensionBrowse : ThorRoute

    /** @param uriString null when the user came from Settings and still has to pick a file. */
    @Serializable
    data class ArchiveRestore(val uriString: String? = null) : ThorRoute
}
