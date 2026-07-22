// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.valhalla.thor.domain.model.AppInfo
import java.io.File

/**
 * Maps Android framework package/application metadata into the framework-free
 * [AppInfo] domain model.
 *
 * Relocated out of `AppInfo`'s companion object so the domain model stays pure
 * Kotlin (no `android.*` imports). Behavior is unchanged from the original
 * `AppInfo.mapToAppInfo`.
 */
fun mapToAppInfo(
    packInfo: PackageInfo,
    appInfo: ApplicationInfo,
    pm: PackageManager,
    isLightweight: Boolean = false
): AppInfo {
    val isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val sharedLibraryFiles = if (!isLightweight) {
        appInfo.sharedLibraryFiles?.toList() ?: emptyList()
    } else {
        emptyList()
    }

    val obbFilePath = if (!isLightweight) {
        val obbFile = File(
            Environment.getExternalStorageDirectory(),
            "Android/obb/${appInfo.packageName}"
        )
        if (obbFile.exists()) obbFile.absolutePath else null
    } else {
        null
    }

    val sharedDataDir = if (!isLightweight) {
        val dataFile = File(
            Environment.getExternalStorageDirectory(),
            "Android/data/${appInfo.packageName}"
        )
        dataFile.absolutePath
    } else {
        ""
    }

    @Suppress("DEPRECATION")
    return AppInfo(
        appName = appInfo.loadLabel(pm).toString(),
        packageName = packInfo.packageName,
        versionName = packInfo.versionName,
        versionCode = packInfo.longVersionCode.toInt(),
        minSdk = appInfo.minSdkVersion,
        targetSdk = appInfo.targetSdkVersion,
        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        installerPackageName = getInstallerPackageName(packInfo.packageName, pm),
        publicSourceDir = appInfo.publicSourceDir,
        splitPublicSourceDirs = appInfo.splitPublicSourceDirs?.toList() ?: emptyList(),
        enabled = appInfo.enabled && (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0,
        dataDir = appInfo.dataDir,
        nativeLibraryDir = appInfo.nativeLibraryDir,
        deviceProtectedDataDir = appInfo.deviceProtectedDataDir,
        sharedLibraryFiles = sharedLibraryFiles,
        obbFilePath = obbFilePath,
        sourceDir = appInfo.sourceDir,
        sharedDataDir = sharedDataDir,
        lastUpdateTime = packInfo.lastUpdateTime,
        firstInstallTime = packInfo.firstInstallTime,
        isDebuggable = isDebuggable,
        isSuspended = (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
        isInstalled = (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    )
}

private fun getInstallerPackageName(packageName: String, pm: PackageManager): String? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }
}
