// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.valhalla.thor.domain.model.AppInfo

@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey val packageName: String,
    val appName: String?,
    val versionName: String?,
    // Int -> Long. No schema version bump or Migration is needed: Room maps both Kotlin Int and
    // Long to SQLite affinity INTEGER, so the exported schema and its identityHash are unchanged
    // (see app/schemas/.../5.json). Rows already cached with a truncated value keep it until the
    // package's lastUpdateTime changes and AppRepositoryImpl re-maps it — acceptable, because the
    // only values that were ever wrong belong to apps declaring versionCodeMajor or a code above
    // Int.MAX, which are vanishingly rare, and forcing a full re-scan on every user to correct
    // them would cost far more than it fixes.
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val isSystem: Boolean,
    val installerPackageName: String?,
    val publicSourceDir: String?,
    val splitPublicSourceDirs: List<String>,
    val enabled: Boolean,
    val dataDir: String?,
    val nativeLibraryDir: String?,
    val deviceProtectedDataDir: String?,
    val sharedLibraryFiles: List<String>?,
    val obbFilePath: String?,
    val sourceDir: String?,
    val sharedDataDir: String,
    val lastUpdateTime: Long,
    val firstInstallTime: Long,
    val isDebuggable: Boolean,
    val isSuspended: Boolean,
    val installSize: Long? = null
) {
    fun toDomain(): AppInfo {
        return AppInfo(
            appName = appName,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            isSystem = isSystem,
            installerPackageName = installerPackageName,
            publicSourceDir = publicSourceDir,
            splitPublicSourceDirs = splitPublicSourceDirs,
            enabled = enabled,
            dataDir = dataDir,
            nativeLibraryDir = nativeLibraryDir,
            deviceProtectedDataDir = deviceProtectedDataDir,
            sharedLibraryFiles = sharedLibraryFiles,
            obbFilePath = obbFilePath,
            sourceDir = sourceDir,
            sharedDataDir = sharedDataDir,
            lastUpdateTime = lastUpdateTime,
            firstInstallTime = firstInstallTime,
            isDebuggable = isDebuggable,
            isSuspended = isSuspended,
            installSize = installSize
        )
    }

    companion object {
        fun fromDomain(appInfo: AppInfo): AppEntity {
            return AppEntity(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                versionName = appInfo.versionName,
                versionCode = appInfo.versionCode,
                minSdk = appInfo.minSdk,
                targetSdk = appInfo.targetSdk,
                isSystem = appInfo.isSystem,
                installerPackageName = appInfo.installerPackageName,
                publicSourceDir = appInfo.publicSourceDir,
                splitPublicSourceDirs = appInfo.splitPublicSourceDirs,
                enabled = appInfo.enabled,
                dataDir = appInfo.dataDir,
                nativeLibraryDir = appInfo.nativeLibraryDir,
                deviceProtectedDataDir = appInfo.deviceProtectedDataDir,
                sharedLibraryFiles = appInfo.sharedLibraryFiles,
                obbFilePath = appInfo.obbFilePath,
                sourceDir = appInfo.sourceDir,
                sharedDataDir = appInfo.sharedDataDir,
                lastUpdateTime = appInfo.lastUpdateTime,
                firstInstallTime = appInfo.firstInstallTime,
                isDebuggable = appInfo.isDebuggable,
                isSuspended = appInfo.isSuspended,
                installSize = appInfo.installSize
            )
        }
    }
}
