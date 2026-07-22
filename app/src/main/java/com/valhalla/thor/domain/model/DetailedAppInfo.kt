// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

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
    val hasWakelockPermission: Boolean = false,
    val signatureSha256: String? = null
)

@Serializable
@Immutable
data class PermissionDetail(
    val name: String,
    val isGranted: Boolean,
    val protectionLevel: String,
    val label: String? = null,
    val description: String? = null
)
