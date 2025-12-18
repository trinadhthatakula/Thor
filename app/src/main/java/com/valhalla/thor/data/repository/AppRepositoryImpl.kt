package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class AppRepositoryImpl(
    private val context: Context
) : AppRepository {

    private val pm = context.packageManager

    override fun getAllApps(): Flow<List<AppInfo>> = flow {
        // ... (Previous lightweight implementation) ...
        // I will include the full method here for completeness in your file
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
        } else {
            pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }

        val list = installedPackages.mapNotNull { packInfo ->
            val appInfo = packInfo.applicationInfo ?: return@mapNotNull null
            mapToAppInfo(packInfo, appInfo, isLightweight = true)
        }
        emit(list)
    }.flowOn(Dispatchers.IO)

    override suspend fun getAppDetails(packageName: String): AppInfo? = withContext(Dispatchers.IO) {
        try {
            val flags = (PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
            val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
            } else {
                pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            val appInfo = packInfo.applicationInfo ?: return@withContext null

            // mapToAppInfo with isLightweight = false triggers the OBB/Data checks
            mapToAppInfo(packInfo, appInfo, isLightweight = false)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getApkDetails(apkPath: String): AppInfo? = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_PERMISSIONS
        val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkPath, flags)
        } ?: return@withContext null

        val appInfo = packInfo.applicationInfo?.apply {
            sourceDir = apkPath
            publicSourceDir = apkPath
        } ?: return@withContext null

        mapToAppInfo(packInfo, appInfo, isLightweight = false).apply {
            // Override with specific APK metadata
            this.appName = pm.getApplicationLabel(appInfo).toString()
            // Note: Icons usually need to be loaded into an ImageView directly or cached,
            // storing the Drawable in a data class is bad practice (memory leaks).
            // We store the path or URI usually. For now, sticking to your String-based model.
        }
    }

    // --- The Mapper ---
    // Single source of truth for converting Android objects to Thor objects
    private fun mapToAppInfo(
        packInfo: android.content.pm.PackageInfo,
        appInfo: ApplicationInfo,
        isLightweight: Boolean
    ): AppInfo {
        @Suppress("DEPRECATION") val mapped = AppInfo(
            appName = appInfo.loadLabel(pm).toString(),
            packageName = packInfo.packageName,
            versionName = packInfo.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packInfo.longVersionCode.toInt() else packInfo.versionCode,
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            installerPackageName = getInstallerPackageName(packInfo.packageName),
            publicSourceDir = appInfo.publicSourceDir,
            splitPublicSourceDirs = appInfo.splitPublicSourceDirs?.toList() ?: emptyList(),
            enabled = appInfo.enabled,
            // enabledState = pm.getApplicationEnabledSetting(packInfo.packageName), // Warning: This can be slow, use cautiously
            dataDir = appInfo.dataDir,
            nativeLibraryDir = appInfo.nativeLibraryDir,
            deviceProtectedDataDir = appInfo.deviceProtectedDataDir,
            sourceDir = appInfo.sourceDir,
            lastUpdateTime = packInfo.lastUpdateTime,
            firstInstallTime = packInfo.firstInstallTime
        )

        // The "Heavy" Logic - Only run if explicitly requested
        if (!isLightweight) {
            mapped.sharedLibraryFiles = appInfo.sharedLibraryFiles?.toList() ?: emptyList()

            // OBB Check
            val obbFile = File(Environment.getExternalStorageDirectory(), "Android/obb/${appInfo.packageName}")
            if (obbFile.exists()) {
                mapped.obbFilePath = obbFile.absolutePath
            }

            // Data Dir Check
            val dataFile = File(Environment.getExternalStorageDirectory(), "Android/data/${appInfo.packageName}")
            mapped.sharedDataDir = dataFile.absolutePath
        }

        return mapped
    }

    private fun getInstallerPackageName(packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
        } catch (e: Exception) {
            null
        }
    }
}