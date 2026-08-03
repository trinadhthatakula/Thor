// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.InstalledAppsPermission

/**
 * Domain port for the `com.android.permission.GET_INSTALLED_APPS` probe.
 *
 * Exists for the same reason [UsageAccessGate] does: the concrete checker is `Context`-bound, and
 * both [com.valhalla.thor.presentation.appList.AppListViewModel] and
 * [com.valhalla.thor.data.repository.AppRepositoryImpl] need the answer on a code path that
 * `AppListViewModelTest` builds on the JVM. `:app` has no mocking library and no Robolectric by
 * policy, so a dependency that cannot be faked by hand is a dependency that takes a test suite
 * offline — which is why this is an interface and not the implementation class.
 *
 * Deliberately *not* nullable-with-a-default at the call sites. An absent probe and a device that
 * does not define the permission both have to answer [InstalledAppsPermission.Unsupported], and
 * making that the value of a missing binding rather than of an explicit fake would let a Koin
 * misconfiguration present as "no device supports this" — a silent, permanent, invisible failure of
 * exactly the feature being added.
 */
interface InstalledAppsPermissionGate {

    /**
     * What the running device says right now.
     *
     * Re-read on every call: the grant is three-state on the ROMs that define it, so a "while in
     * use" grant is [InstalledAppsPermission.Granted] in the foreground and stops being true the
     * moment Thor is backgrounded.
     */
    fun state(): InstalledAppsPermission
}
