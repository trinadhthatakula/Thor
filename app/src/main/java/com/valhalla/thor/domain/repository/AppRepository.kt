package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppInfo
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

    // Parser for XAPK/APK installation features
    suspend fun getApkDetails(apkPath: String): AppInfo?
}