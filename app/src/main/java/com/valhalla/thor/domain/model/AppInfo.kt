package com.valhalla.thor.domain.model

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
@Immutable
data class AppInfo(
    val appName: String? = null,
    val packageName: String = "",
    val versionName: String? = "",
    val versionCode: Int = 0,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val isSystem: Boolean = false,
    val installerPackageName: String? = null,
    val publicSourceDir: String? = null,
    val splitPublicSourceDirs: List<String> = emptyList(),
    val enabled: Boolean = true,
    val enabledState: Int = COMPONENT_ENABLED_STATE_DEFAULT,
    val dataDir: String? = null,
    val nativeLibraryDir: String? = null,
    val deviceProtectedDataDir: String? = null,
    val sharedLibraryFiles: List<String>? = emptyList(),
    val obbFilePath: String? = null,
    val sourceDir: String? = null,
    val sharedDataDir: String = "",
    val lastUpdateTime: Long = 0L,
    val firstInstallTime: Long = 0L,
    val isDebuggable: Boolean = false,
    val isSuspended: Boolean = false,
) {
    companion object {

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
                enabled = appInfo.enabled,
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
                isSuspended = (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
            )
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