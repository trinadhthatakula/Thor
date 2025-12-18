package com.valhalla.thor.domain.model

data class AppInstallable(
    val name: String,
    val apkPath: String,
    val isDebuggable: Boolean
)

