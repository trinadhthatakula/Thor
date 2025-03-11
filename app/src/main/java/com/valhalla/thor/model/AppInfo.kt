package com.valhalla.thor.model

import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

@kotlinx.serialization.Serializable
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
    var sharedDataDir: String = ""
)

fun AppInfo.formattedAppName() = appName?.replace(" ", "_") ?: packageName