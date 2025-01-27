package com.valhalla.thor.model

@kotlinx.serialization.Serializable
data class AppInfo(
    var appName: String? = null,
    var packageName: String = "",
    var versionName: String? = "",
    var versionCode: Int = 0,
    var isSystem: Boolean = false,
    var installerPackageName: String? = null,
    var publicSourceDir: String? = null
)