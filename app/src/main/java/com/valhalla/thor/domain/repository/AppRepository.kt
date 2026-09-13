// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ComponentSnapshot
import com.valhalla.thor.domain.model.DetailedAppInfo
import kotlinx.coroutines.flow.Flow

interface AppRepository {
    /**
     * Fetches all installed applications.
     * Returns a Flow to allow emitting updates if packages change (optional),
     * or just a single emission for now.
     */
    fun getAllApps(): Flow<List<AppInfo>>

    /**
     * Get details for a specific package.
     * This is where we will do the heavy lifting (OBB checks, etc.)
     * so we don't slow down the main list.
     */
    suspend fun getAppDetails(packageName: String): AppInfo?

    /**
     * Fetches heavy details (activities, permissions, services, etc.) dynamically.
     */
    suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo?

    /**
     * Re-reads just the four component lists.
     *
     * Exists so that switching one component off does not have to pay for the whole of
     * [getDetailedAppInfo] to show the result: that call also hashes the signing certificate, walks
     * the native library directory and makes two binder calls per requested permission, none of
     * which a component toggle can have changed. Returns `null` on the same terms as
     * [getDetailedAppInfo] — the package is gone, or `PackageManager` refused.
     */
    suspend fun getComponentDetails(packageName: String): ComponentSnapshot?

    // Parser for XAPK/APK installation features
    suspend fun getApkDetails(apkPath: String): AppInfo?

    /** Persist freshly-computed total install sizes into the app cache. */
    suspend fun updateInstallSizes(sizes: Map<String, Long>)
}