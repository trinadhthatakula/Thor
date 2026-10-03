// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import com.valhalla.thor.domain.model.InstalledManagerInfo
import com.valhalla.thor.domain.model.RootManagerShortcut
import com.valhalla.thor.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal data class ManagerShortcuts(
    val installed: List<InstalledManagerInfo>,
    val selected: RootManagerShortcut?
)

private data class ManagerShortcutCache(
    val selectedPackage: String?,
    val refreshRevision: Long,
    val shortcuts: ManagerShortcuts
)

/** Reuses discovery for unrelated preferences while keeping each preference snapshot intact. */
internal fun preferencesWithManagerShortcuts(
    preferences: Flow<UserPreferences>,
    managerRefreshRevision: Flow<Long>,
    discover: (String?) -> ManagerShortcuts
): Flow<Pair<UserPreferences, ManagerShortcuts>> = flow {
    // Collection-local state also gives WhileSubscribed a fresh lookup after collection restarts.
    var cached: ManagerShortcutCache? = null
    emitAll(combine(preferences, managerRefreshRevision) { prefs, revision ->
        val selectedPackage = prefs.selectedRootManagerPackage
        val previous = cached
        val shortcuts = if (previous != null &&
            previous.selectedPackage == selectedPackage && previous.refreshRevision == revision
        ) {
            previous.shortcuts
        } else {
            discover(selectedPackage).also {
                cached = ManagerShortcutCache(selectedPackage, revision, it)
            }
        }
        prefs to shortcuts
    })
}
