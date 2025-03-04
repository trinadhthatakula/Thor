package com.valhalla.thor.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

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
            if (p != null && a != null && (a.flags and ApplicationInfo.FLAG_SYSTEM == 0)) {
                res.add(
                    AppInfo().apply {
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
                        splitPublicSourceDirs = a.splitPublicSourceDirs?.map { it } ?: emptyList()
                        enabled = a.enabled
                        enabledState =
                            context.packageManager.getApplicationEnabledSetting(p.packageName)
                        dataDir = a.dataDir
                        nativeLibraryDir = a.nativeLibraryDir
                        deviceProtectedDataDir = a.deviceProtectedDataDir
                        sourceDir = a.sourceDir
                        sharedLibraryFiles = processSharedLibraryFiles(a.sharedLibraryFiles)
                        obbFilePath = if (File(
                                Environment.getExternalStorageDirectory(),
                                "Android/obb/${a.packageName}"
                            ).exists()
                        ) {
                            File(
                                Environment.getExternalStorageDirectory(),
                                "Android/obb/${a.packageName}"
                            ).absolutePath
                        } else {
                            null
                        }
                        sharedDataDir = File(
                            Environment.getExternalStorageDirectory(),
                            "Android/data/${a.packageName}"
                        ).absolutePath
                    }
                )
            }
        }
        return res
    }

    private fun processSharedLibraryFiles(sharedLibraryFiles: Array<String>?): List<String> {
        val libFiles = mutableListOf<String>()
        sharedLibraryFiles?.forEach {
            libFiles.add(it)
        }
        return libFiles
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
            if (p != null && a != null && (a.flags and ApplicationInfo.FLAG_SYSTEM == 1)) {
                res.add(
                    AppInfo().apply {
                        appName = a.loadLabel(context.packageManager).toString()
                        packageName = p.packageName
                        versionName = p.versionName
                        versionCode = p.versionCode
                        isSystem = true
                        installerPackageName =
                            context.packageManager.getInstallerPackageName(p.packageName)
                        publicSourceDir = a.publicSourceDir
                        splitPublicSourceDirs = a.splitPublicSourceDirs?.map { it } ?: emptyList()
                        enabled = a.enabled
                        enabledState =
                            context.packageManager.getApplicationEnabledSetting(p.packageName)
                        dataDir = a.dataDir
                        nativeLibraryDir = a.nativeLibraryDir
                        deviceProtectedDataDir = a.deviceProtectedDataDir
                        sourceDir = a.sourceDir
                        sharedLibraryFiles = processSharedLibraryFiles(a.sharedLibraryFiles)
                        obbFilePath = if (File(
                                Environment.getExternalStorageDirectory(),
                                "Android/obb/${a.packageName}"
                            ).exists()
                        ) {
                            File(
                                Environment.getExternalStorageDirectory(),
                                "Android/obb/${a.packageName}"
                            ).absolutePath
                        } else {
                            null
                        }
                        sharedDataDir = File(
                            Environment.getExternalStorageDirectory(),
                            "Android/data/${a.packageName}"
                        ).absolutePath
                    }
                )
            }
        }
        return res
    }

}

