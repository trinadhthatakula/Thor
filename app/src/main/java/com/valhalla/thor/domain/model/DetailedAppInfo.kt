// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class DetailedAppInfo(
    val appInfo: AppInfo,
    /**
     * The four component lists.
     *
     * Was four `List<String>` of class names. The names alone could be listed and copied and
     * nothing else, because every question the Components tab now asks of a row — is it exported,
     * is it switched off, is it guarded by a permission — was thrown away by the mapper one line
     * after `PackageManager` handed it over.
     */
    val components: ComponentSnapshot = ComponentSnapshot(),
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
