// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * @param versionCode the file's Android version code, or null when it could not be read at all.
 *   Null is *unknown*, which is not the same as `0`: a real APK whose manifest omits
 *   `android:versionCode` parses as `0`, and Android compares that value numerically like any
 *   other — installing it over a positive code is a genuine downgrade. Only the sidecar-only
 *   fallback, which has no APK to parse, can produce null. See [isVersionDowngrade].
 */
data class AppMetadata(
    val label: String,
    val packageName: String,
    val version: String,
    val versionCode: Long?,
    val iconPath: String?,
    val permissions: List<String> = emptyList(),
    /**
     * The APK manifest's target SDK, or null when no APK could be parsed.
     *
     * This deliberately has no sidecar equivalent: bundle JSON is untrusted metadata and must not
     * enable an install-policy exception. A parsed APK may legitimately report 0, which remains a
     * known value rather than being rewritten to null.
     */
    val targetSdk: Int? = null,
)
