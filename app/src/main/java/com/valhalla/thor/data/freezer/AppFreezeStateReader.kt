// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.isFrozen
import org.koin.core.annotation.Single

/**
 * Reads an app's live freeze state. The single place that answers "is this app frozen?",
 * replacing the inline copy that used to live in FreezerShortcutManager.
 *
 * MATCH_DISABLED_COMPONENTS so a disabled app is still readable; FLAG_SUSPENDED (API 24+)
 * catches the suspend-mode case.
 */
@Single
class AppFreezeStateReader(
    private val packageManager: PackageManager,
) {
    fun stateOf(packageName: String): FreezeState = try {
        val info = packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_DISABLED_COMPONENTS
        )
        val suspended = (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        if (isFrozen(info.enabled, suspended)) FreezeState.FROZEN else FreezeState.ACTIVE
    } catch (e: PackageManager.NameNotFoundException) {
        FreezeState.ABSENT
    }
}
