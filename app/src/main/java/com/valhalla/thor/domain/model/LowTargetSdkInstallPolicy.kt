// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.domain.repository.InstallMode

/**
 * Android's default target-SDK floor for new installs, not the APK's minSdkVersion.
 * OEM package-manager restrictions can still differ; the installer must preserve their errors.
 */
internal fun minimumInstallTargetSdk(deviceSdk: Int): Int? = when {
    deviceSdk >= 35 -> 24
    deviceSdk == 34 -> 23
    else -> null
}

/** Only shell/root can retain the bypass flag on production Android builds. */
internal fun supportsLowTargetSdkBypass(mode: InstallMode, deviceSdk: Int): Boolean =
    deviceSdk >= 34 && (mode == InstallMode.ROOT || mode == InstallMode.SHIZUKU)

/** Unknown/sidecar-only metadata is not evidence on which to offer privileged consent. */
internal fun requiresLowTargetSdkBypass(targetSdk: Int?, deviceSdk: Int): Boolean {
    val minimum = minimumInstallTargetSdk(deviceSdk) ?: return false
    return targetSdk != null && targetSdk in 0 until minimum
}
