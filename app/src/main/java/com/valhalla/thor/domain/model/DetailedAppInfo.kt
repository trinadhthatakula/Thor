package com.valhalla.thor.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class DetailedAppInfo(
    val appInfo: AppInfo,
    val activities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
    val permissions: List<PermissionDetail> = emptyList(),
    val nativeLibs: List<String> = emptyList(),
    val reqFeatures: List<String> = emptyList(),
    val hasWakelockPermission: Boolean = false
)

@Serializable
@Immutable
data class PermissionDetail(
    val name: String,
    val isGranted: Boolean,
    val protectionLevel: String
)
