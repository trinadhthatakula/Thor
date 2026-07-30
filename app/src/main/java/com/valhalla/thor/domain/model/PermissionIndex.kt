// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.compose.runtime.Immutable

/**
 * "Which apps can use the camera?", answered once for the whole device.
 *
 * Built lazily — only while [FilterType.Permission] is selected — because it costs a
 * `getInstalledPackages(GET_PERMISSIONS)` sweep plus a `getPermissionInfo` per distinct *custom*
 * permission name. Doing that at list time, per app, would make scrolling pay for a filter most
 * sessions never touch; caching it in Room would make a schema migration out of a value that any
 * install, uninstall or update invalidates. Holding it in memory and rebuilding it when the app list
 * itself changes is the size of job the feature actually is — see `observePermissionFilter`.
 *
 * Only *dangerous* (runtime) permissions are indexed. `INTERNET` matching 400 apps is not a filter,
 * and the question users ask is about the capabilities the system itself gates behind a prompt.
 */
@Immutable
data class PermissionIndex(
    /** Permission-group name (e.g. `android.permission-group.CAMERA`) -> packages that declare it. */
    val packagesByGroup: Map<String, Set<String>> = emptyMap(),
    /**
     * Group name -> the label the *platform* already ships for it, in the user's language.
     *
     * Read from `PermissionGroupInfo.loadLabel` rather than translated in Thor: the OS has these in
     * every locale it supports, and they are the exact words the permission dialog uses, so the
     * chips read the same as the prompts the user has already seen.
     */
    val groupLabels: Map<String, String> = emptyMap()
) {
    /** Group names ordered by their localised label — chip order, resolved once at build time. */
    val orderedGroups: List<String> =
        packagesByGroup.keys.sortedBy { groupLabels[it]?.lowercase() ?: it }

    fun packagesFor(group: String): Set<String> = packagesByGroup[group].orEmpty()

    val isEmpty: Boolean get() = packagesByGroup.isEmpty()
}
