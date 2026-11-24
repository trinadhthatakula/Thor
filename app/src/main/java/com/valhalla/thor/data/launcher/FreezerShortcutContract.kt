// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.launcher

/** Pure contract shared between the shortcut publisher and the launch trampoline. */
object FreezerShortcutContract {
    const val EXTRA_ACTION = "com.valhalla.thor.extra.FREEZER_SHORTCUT_ACTION"
    const val EXTRA_PACKAGE = "com.valhalla.thor.extra.FREEZER_SHORTCUT_PACKAGE"
    const val EXTRA_PROFILE_ID = "com.valhalla.thor.extra.FREEZER_SHORTCUT_PROFILE_ID"

    const val EXTRA_PROFILE_ICON = "com.valhalla.thor.extra.PROFILE_ICON"
    const val EXTRA_PREVIOUS_ICON = "com.valhalla.thor.extra.PREVIOUS_PROFILE_ICON"

    const val ACTION_LAUNCH = "launch"
    const val ACTION_FREEZE_ALL = "freeze_all"
    const val ACTION_UNFREEZE_ALL = "unfreeze_all"
    const val ACTION_FREEZE_PROFILE = "freeze_profile"

    const val SHORTCUT_FREEZE_ALL = "freezer_freeze_all"
    const val SHORTCUT_UNFREEZE_ALL = "freezer_unfreeze_all"
    private const val APP_SHORTCUT_PREFIX = "freezer_app_"
    private const val PROFILE_SHORTCUT_PREFIX = "freezer_profile_"

    // Launcher-tile ARGB colours for the bulk shortcuts — shared by the pinned adaptive icon and the
    // in-app "Shortcuts" preview so the two always match.
    val FREEZE_TILE_COLOR: Int = 0xFF29B6F6.toInt()   // sky blue
    val UNFREEZE_TILE_COLOR: Int = 0xFFFB8C00.toInt() // orange

    /** Stable, package-scoped id for a per-app frozen-app shortcut. */
    fun appShortcutId(packageName: String): String = "$APP_SHORTCUT_PREFIX$packageName"

    fun profileShortcutId(profileId: Long): String = "$PROFILE_SHORTCUT_PREFIX$profileId"

    fun profileIdFromShortcutId(id: String): Long? =
        id.takeIf { it.startsWith(PROFILE_SHORTCUT_PREFIX) }
            ?.removePrefix(PROFILE_SHORTCUT_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

    /** Only Freezer-owned pinned shortcuts are affected by the global disable switch. */
    fun isFreezerShortcutId(id: String): Boolean =
        id == SHORTCUT_FREEZE_ALL || id == SHORTCUT_UNFREEZE_ALL ||
            id.startsWith(APP_SHORTCUT_PREFIX) || profileIdFromShortcutId(id) != null

    /** Normalize a raw intent extra to a known action, or null if unrecognized. */
    fun parseAction(raw: String?): String? = when (raw) {
        ACTION_LAUNCH, ACTION_FREEZE_ALL, ACTION_UNFREEZE_ALL, ACTION_FREEZE_PROFILE -> raw
        else -> null
    }
}
