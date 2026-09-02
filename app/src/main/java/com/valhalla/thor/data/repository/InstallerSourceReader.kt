// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.pm.PackageManager
import android.os.Build

internal fun PackageManager.installerPackageNameOf(packageName: String): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val sourceInfo = getInstallSourceInfo(packageName)
        sourceInfo.installingPackageName ?: sourceInfo.initiatingPackageName
    } else {
        @Suppress("DEPRECATION")
        getInstallerPackageName(packageName)
    }
} catch (_: Exception) {
    null
}
