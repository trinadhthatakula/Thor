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
    val versionCode: Int,
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
