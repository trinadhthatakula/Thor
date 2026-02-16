package com.valhalla.thor.domain.model

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.os.Build
import android.os.Environment
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AppInfo(
    var appName: String? = null,
    var packageName: String = "",
    var versionName: String? = "",
    var versionCode: Int = 0,
    var minSdk: Int = 0,
    var targetSdk: Int = 0,
    var isSystem: Boolean = false,
    var installerPackageName: String? = null,
    var publicSourceDir: String? = null,
    var splitPublicSourceDirs: List<String> = emptyList(),
    var enabled: Boolean = true,
    var enabledState: Int = COMPONENT_ENABLED_STATE_DEFAULT,
    var dataDir: String? = null,
    var nativeLibraryDir: String? = null,
    var deviceProtectedDataDir: String? = null,
    var sharedLibraryFiles: List<String>? = emptyList(),
    var obbFilePath: String? = null,
    var sourceDir: String? = null,
    var sharedDataDir: String = "",
    var lastUpdateTime: Long = 0L,
    var firstInstallTime: Long = 0L,
    val isDebuggable: Boolean = false,
) {
    companion object {

        fun mapToAppInfo(
            packInfo: PackageInfo,
            appInfo: ApplicationInfo,
            pm: PackageManager,
            isLightweight: Boolean = false
        ): AppInfo {
            val isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            @Suppress("DEPRECATION") val mapped = AppInfo(
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
                enabled = appInfo.enabled,
                // enabledState = pm.getApplicationEnabledSetting(packInfo.packageName), // Warning: This can be slow, use cautiously
                dataDir = appInfo.dataDir,
                nativeLibraryDir = appInfo.nativeLibraryDir,
                deviceProtectedDataDir = appInfo.deviceProtectedDataDir,
                sourceDir = appInfo.sourceDir,
                lastUpdateTime = packInfo.lastUpdateTime,
                firstInstallTime = packInfo.firstInstallTime,
                isDebuggable = isDebuggable
            )

            // The "Heavy" Logic - Only run if explicitly requested
            if (!isLightweight) {
                mapped.sharedLibraryFiles = appInfo.sharedLibraryFiles?.toList() ?: emptyList()

                // OBB Check
                val obbFile = File(
                    Environment.getExternalStorageDirectory(),
                    "Android/obb/${appInfo.packageName}"
                )
                if (obbFile.exists()) {
                    mapped.obbFilePath = obbFile.absolutePath
                }

                // Data Dir Check
                val dataFile = File(
                    Environment.getExternalStorageDirectory(),
                    "Android/data/${appInfo.packageName}"
                )
                mapped.sharedDataDir = dataFile.absolutePath
            }

            return mapped
        }

        fun getInstallerPackageName(packageName: String, pm: PackageManager): String? {
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

    }
}

fun AppInfo.formattedAppName() = appName?.replace(" ", "_") ?: packageName