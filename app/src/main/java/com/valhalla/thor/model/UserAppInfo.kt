package com.valhalla.thor.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

@Suppress("DEPRECATION")
class UserAppInfo(private val context: Context) {

    @SuppressLint("QueryPermissionsNeeded")
    fun getUserApps(): List<AppInfo> {
        val res = ArrayList<AppInfo>()
        val packs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getInstalledPackages(0)
        }
        for (i in packs.indices) {
            val p = packs[i]
            val a = p.applicationInfo
            if ((a?.flags?:0) and ApplicationInfo.FLAG_SYSTEM == 0) {
                val appInfo = AppInfo()
                appInfo.appName = p.applicationInfo?.loadLabel(context.packageManager).toString()
                appInfo.packageName = p.packageName
                appInfo.versionName = p.versionName
                appInfo.versionCode = p.versionCode
                appInfo.isSystem = false
                appInfo.installerPackageName = context.packageManager.getInstallerPackageName(p.packageName)
                appInfo.publicSourceDir = p.applicationInfo?.publicSourceDir
                res.add(appInfo)
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
        for (i in packs.indices) {
            val p = packs[i]
            val a = p.applicationInfo
            if ((a?.flags?:0) and ApplicationInfo.FLAG_SYSTEM == 1) {
                val appInfo = AppInfo()
                appInfo.appName = p.applicationInfo?.loadLabel(context.packageManager).toString()
                appInfo.packageName = p.packageName
                appInfo.versionName = p.versionName
                appInfo.versionCode =  if (Build.VERSION.SDK_INT>=28 ) p.longVersionCode.toInt() else p.versionCode
                appInfo.isSystem = true
                res.add(appInfo)
            }
        }
        return res
    }

}
