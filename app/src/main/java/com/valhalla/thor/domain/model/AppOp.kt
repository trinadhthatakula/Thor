// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** App Ops modes are distinct from Android's runtime-permission grant state. */
enum class AppOpMode(val platformValue: Int, val shellToken: String?) {
    ALLOW(0, "allow"),
    IGNORE(1, "ignore"),
    DENY(2, "deny"),
    DEFAULT(3, "default"),
    FOREGROUND(4, "foreground"),
    UNKNOWN(-1, null);

    companion object {
        fun fromPlatformValue(value: Int): AppOpMode =
            entries.firstOrNull { it.platformValue == value } ?: UNKNOWN

        fun fromShellToken(value: String): AppOpMode = when (value.lowercase()) {
            "allow" -> ALLOW
            "ignore" -> IGNORE
            "deny", "error", "errored" -> DENY
            "default" -> DEFAULT
            "foreground" -> FOREGROUND
            else -> UNKNOWN
        }
    }
}

enum class AppOpScope { PACKAGE, UID }

/** One controlling operation from the device's catalog; aliases share [code]. */
data class AppOpDefinition(
    val code: Int,
    val debugName: String,
    val publicName: String?,
    val aliases: List<String>,
    val relatedPermissions: List<String>,
    val platformDefault: AppOpMode,
    val allowsReset: Boolean,
    val aliasCodes: List<Int> = emptyList(),
)

/** Modes actually recorded for each scope; null means no entry was returned. */
data class AppOpEntry(
    val definition: AppOpDefinition,
    val packageMode: AppOpMode?,
    val uidMode: AppOpMode?,
    val observed: Boolean,
    val permissionRequested: Boolean,
) {
    val hasUidOverride: Boolean
        get() = uidMode != null && uidMode != definition.platformDefault

    val displayedMode: AppOpMode
        get() = uidMode?.takeIf { it != definition.platformDefault }
            ?: packageMode ?: definition.platformDefault

    val isChanged: Boolean
        get() = (packageMode != null && packageMode != definition.platformDefault) ||
            (uidMode != null && uidMode != definition.platformDefault)

    val isRelevant: Boolean
        get() = permissionRequested || observed || isChanged
}

data class AppOpsSnapshot(
    val userId: Int,
    val uid: Int,
    val entries: List<AppOpEntry>,
    val canEdit: Boolean,
    val sharedUidPackages: List<String> = emptyList(),
    val unsupportedOperationCount: Int = 0,
)
