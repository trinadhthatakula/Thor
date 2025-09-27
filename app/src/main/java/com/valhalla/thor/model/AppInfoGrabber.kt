package com.valhalla.thor.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

@Suppress("DEPRECATION")
class AppInfoGrabber(private val context: Context) {

    private val _systemApps = MutableStateFlow(emptyList<AppInfo>())
    val systemApps = _systemApps.asStateFlow()

    private val _userApps = MutableStateFlow(emptyList<AppInfo>())
    val userApps = _userApps.asStateFlow()

    @SuppressLint("QueryPermissionsNeeded")
    fun getUserApps() {
        val res = ArrayList<AppInfo>()
        val packs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getInstalledPackages(0)
        }
        val batchSize = 5
        packs.indices.forEachIndexed { index, i ->
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
                        splitPublicSourceDirs =
                            a.splitPublicSourceDirs?.map { it } ?: emptyList()
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
                        lastUpdateTime = p.lastUpdateTime
                        firstInstallTime = p.firstInstallTime
                    }
                )
            }

            if (index % batchSize == 0 || index == packs.size - 1) {
                _userApps.value = (res)
            }

        }
    }

    private fun processSharedLibraryFiles(sharedLibraryFiles: Array<String>?): List<String> {
        val libFiles = mutableListOf<String>()
        sharedLibraryFiles?.forEach {
            libFiles.add(it)
        }
        return libFiles
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun getSystemApps() {
        val res = ArrayList<AppInfo>()
        val packs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getInstalledPackages(0)
        }
        val batchSize = 5
        packs.indices.forEachIndexed { index, i ->
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
                        splitPublicSourceDirs =
                            a.splitPublicSourceDirs?.map { it } ?: emptyList()
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
                        lastUpdateTime = p.lastUpdateTime
                        firstInstallTime = p.firstInstallTime
                    }
                )
            }


            if (index % batchSize == 0 || index == packs.size - 1) {
                _systemApps.value = (res)
            }

        }


    }


    fun getApkInfo(apkPath: String): ApkDetails? {
        val packageManager = context.packageManager

        // Specify flags to get the information you need, especially GET_PERMISSIONS.
        val flags = PackageManager.GET_PERMISSIONS

        val packageInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkPath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkPath, flags)
        }

        if (packageInfo == null) {
            // The APK is likely corrupt or invalid.
            return null
        }

        // The ApplicationInfo object holds most of the metadata.
        // It needs to be updated with the APK path for resources like the icon and label to be loaded correctly.
        val appInfo: ApplicationInfo? = packageInfo.applicationInfo?.apply {
            this.sourceDir = apkPath
            publicSourceDir = apkPath
        }

        return if (appInfo != null) {
            // Extract all the details.
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = packageManager.getApplicationIcon(appInfo)
            val packageName = packageInfo.packageName
            val versionName = packageInfo.versionName

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            val permissions = packageInfo.requestedPermissions?.toList()
            val minSdk = appInfo.minSdkVersion
            val targetSdk = appInfo.targetSdkVersion

            ApkDetails(
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                appIcon = appIcon,
                permissions = permissions,
                minSdk = minSdk,
                targetSdk = targetSdk
            )
        } else null
    }

    fun loadApps() {
        getUserApps()
        getSystemApps()
    }

}
