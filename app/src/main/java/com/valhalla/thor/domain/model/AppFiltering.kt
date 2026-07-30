// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * Applies the app-list filter, the same way [sortApps] applies the sort.
 *
 * Lifted out of `AppListViewModel.processList` when the permission filter landed: the branches
 * encode real rules — "Frozen" is `!enabled`, "Suspended" is an *overlapping* set rather than a
 * third state, an unknown chip passes everything through, a permission group that has not been
 * indexed yet matches nothing rather than everything — and none of them were reachable from a test
 * while they lived inside a private method on a ViewModel with eleven constructor dependencies and
 * no fakes. The rules are carried over verbatim; only the permission branch is new.
 *
 * @param selectedFilter the chip value. `"All"` is the pass-through for every filter type.
 * @param permissionIndex only consulted for [FilterType.Permission]; an empty index matches nothing.
 */
fun filterApps(
    apps: List<AppInfo>,
    filterType: FilterType,
    selectedFilter: String,
    permissionIndex: PermissionIndex = PermissionIndex()
): List<AppInfo> {
    if (selectedFilter == ALL_FILTER) return apps
    return when (filterType) {
        FilterType.Source -> apps.filter { it.installerPackageName == selectedFilter }

        FilterType.State -> when (selectedFilter) {
            // Active and Suspended overlap on purpose: suspension does not disable an app, so a
            // suspended app is still enabled and still appears under Active. That is the shipped
            // behaviour and users read "Suspended" as a lens, not a fourth exclusive state.
            "Active" -> apps.filter { it.enabled }
            "Frozen" -> apps.filter { !it.enabled }
            "Suspended" -> apps.filter { it.isSuspended }
            // An unrecognised state chip passes everything through rather than showing an empty
            // list: the value is persisted in DataStore, so a chip removed in a later version would
            // otherwise present as "you have no apps" on first launch after the update.
            else -> apps
        }

        FilterType.Permission -> {
            // Empty while the index is still building. Matching nothing is the honest answer —
            // matching everything would show an unfiltered list under a Camera chip and then
            // silently shrink a moment later.
            val declaring = permissionIndex.packagesFor(selectedFilter)
            apps.filter { it.packageName in declaring }
        }
    }
}

/** The pass-through chip value, persisted verbatim in DataStore. */
const val ALL_FILTER = "All"
