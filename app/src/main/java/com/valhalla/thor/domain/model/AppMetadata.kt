package com.valhalla.thor.domain.model

data class AppMetadata(
    val label: String,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val iconPath: String?,
    val permissions: List<String> = emptyList()
)
