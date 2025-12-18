package com.valhalla.thor.data.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.domain.model.AppInstallable
import java.io.File

/**
 * Checks APKs for the debuggable flag.
 * Keep this decoupled so you can test it easily.
 */
class PackageVerifier(private val packageManager: PackageManager) {
    fun scanForDebuggableApps(apkPaths: List<String>): List<AppInstallable> {
        return apkPaths.mapNotNull { path ->
            val file = File(path)
            if (!file.exists()) return@mapNotNull null

            // Use GET_META_DATA or 0. parsing headers is expensive, do not do on UI thread.
            val info = packageManager.getPackageArchiveInfo(path, 0)
            val appInfo = info?.applicationInfo

            // Bitwise check for the debuggable flag
            val isDebuggable = appInfo?.let {
                (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } ?: false

            if (info != null) {
                AppInstallable(
                    name = appInfo?.packageName ?: file.name,
                    apkPath = path,
                    isDebuggable = isDebuggable
                )
            } else null
        }
    }
}