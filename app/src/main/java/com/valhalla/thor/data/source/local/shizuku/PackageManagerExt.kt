package com.valhalla.thor.data.source.local.shizuku

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
import com.valhalla.thor.domain.model.AppInfo


private fun PackageManager.getUninstalledPackages(installedPackages: List<PackageInfo>): List<PackageInfo> {
    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES

    // Get uninstalled packages + installed packages
    val uninstalledPackages = getPackages(flags).toSet()

    val installed = installedPackages.map { it.packageName }
    val minus = uninstalledPackages.filter { !installed.contains(it.packageName) }

    // Return only apps that have been uninstalled
    return minus.toList()
}

fun PackageManager.getAllPackagesInfo(): List<AppInfo> {
    val installedPackages = getInstalledPackages()
    val uninstalledPackages = getUninstalledPackages(installedPackages)

    val all = (uninstalledPackages.map { app ->
        val appInfo = app.applicationInfo
        if (appInfo != null)
            AppInfo.mapToAppInfo(
                packInfo = app,
                appInfo = appInfo,
                pm = this
            )
        else null
    } + installedPackages.map { app ->
        val appInfo = app.applicationInfo
        if (appInfo != null)
            AppInfo.mapToAppInfo(
                packInfo = app,
                appInfo = appInfo,
                pm = this
            )
        else null
    }).filterNotNull()

    return all
}

fun PackageManager.getInstalledPackages(): List<PackageInfo> {
    val flags = PackageManager.GET_META_DATA
    return getPackages(flags)
}

private fun PackageManager.getPackages(flags: Int): List<PackageInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        this.getInstalledPackages(
            PackageManager.PackageInfoFlags.of(flags.toLong())
        )
    } else {
        this.getInstalledPackages(flags)
    }
}

fun PackageManager.getInfoForPackage(
    packageName: String,
): PackageInfo? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            this.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA
            )
        }
    } catch (e: NameNotFoundException) {
        null
    }
}
