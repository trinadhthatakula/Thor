package com.valhalla.thor.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

@Suppress("DEPRECATION")
class AppInfoGrabber(private val context: Context) {

    val allApps
        get() = getUserApps() + getSystemApps()

    @SuppressLint("QueryPermissionsNeeded")
    fun getUserApps(): List<AppInfo> {
        val res = ArrayList<AppInfo>()
        val packs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getInstalledPackages(0)
        }
        packs.indices.forEach { i ->
            val p = packs[i]
            val a = p.applicationInfo
            if (p != null && a!=null && (a.flags and ApplicationInfo.FLAG_SYSTEM == 0)) {
                res.add(AppInfo().apply {
                    appName = a.loadLabel(context.packageManager).toString()
                    packageName = p.packageName
                    versionName = p.versionName
                    versionCode = p.versionCode
                    minSdk = a.minSdkVersion
                    targetSdk = a.targetSdkVersion
                    isSystem = false
                    installerPackageName =
                        context.packageManager.getInstallerPackageName(p.packageName)
                    publicSourceDir = a.publicSourceDir
                    splitPublicSourceDirs = a.splitPublicSourceDirs?.map { it }?:emptyList()
                    enabled = a.enabled
                    enabledState = context.packageManager.getApplicationEnabledSetting(p.packageName)
                })
            }
        }
        return res
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun getSystemApps(): List<AppInfo> {
        val res = ArrayList<AppInfo>()
        val packs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getInstalledPackages(0)
        }
        packs.indices.forEach { i ->
            val p = packs[i]
            val a = p.applicationInfo
            if (p != null && a!=null && (a.flags and ApplicationInfo.FLAG_SYSTEM == 1)) {
                res.add(AppInfo().apply {
                    appName = a.loadLabel(context.packageManager).toString()
                    packageName = p.packageName
                    versionName = p.versionName
                    versionCode = p.versionCode
                    isSystem = true
                    installerPackageName =
                        context.packageManager.getInstallerPackageName(p.packageName)
                    publicSourceDir = a.publicSourceDir
                    splitPublicSourceDirs = a.splitPublicSourceDirs?.map { it }?:emptyList()
                    enabled = a.enabled
                    enabledState = context.packageManager.getApplicationEnabledSetting(p.packageName)
                })
            }
        }
        return res
    }

}
