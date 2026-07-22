// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppInfo
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

    // Parser for XAPK/APK installation features
    suspend fun getApkDetails(apkPath: String): AppInfo?

    /** Persist freshly-computed total install sizes into the app cache. */
    suspend fun updateInstallSizes(sizes: Map<String, Long>)
}