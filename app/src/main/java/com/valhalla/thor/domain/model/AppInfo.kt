// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

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
    // Value of PackageManager.COMPONENT_ENABLED_STATE_DEFAULT (0); inlined to
    // keep this domain model free of android.* imports.
    val enabledState: Int = 0,
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
    val bloatRecommendation: String? = null,
    val bloatDescription: String? = null,
    val isInstalled: Boolean = true,
    val isUadLoadFailed: Boolean = false,
    /** Total install size in bytes (app + data + cache). null = not yet computed. */
    val installSize: Long? = null,
)

fun AppInfo.formattedAppName() = appName?.replace(" ", "_") ?: packageName
