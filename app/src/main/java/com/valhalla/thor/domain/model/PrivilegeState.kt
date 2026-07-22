// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * Snapshot of privilege availability + the resolved active mode.
 *
 * [isReady] is false until the first probe completes, so consumers can tell
 * "not probed yet" apart from "probed, nothing available" (avoids a flash of the
 * no-privilege UI on cold start).
 */
data class PrivilegeState(
    val root: Boolean = false,
    val shizuku: Boolean = false,
    val dhizuku: Boolean = false,
    val active: PrivilegeMode = PrivilegeMode.NONE,
    val isReady: Boolean = false
) {
    val hasAnyPrivilege: Boolean get() = active != PrivilegeMode.NONE
}

/**
 * Resolve the effective privilege mode: the user's [preferred] mode when it is
 * actually available, otherwise the first available in Root -> Shizuku -> Dhizuku
 * order, otherwise [PrivilegeMode.NONE]. A null or NONE [preferred] means "auto".
 */
fun resolvePrivilegeMode(
    preferred: PrivilegeMode?,
    root: Boolean,
    shizuku: Boolean,
    dhizuku: Boolean
): PrivilegeMode {
    when (preferred) {
        PrivilegeMode.ROOT -> if (root) return PrivilegeMode.ROOT
        PrivilegeMode.SHIZUKU -> if (shizuku) return PrivilegeMode.SHIZUKU
        PrivilegeMode.DHIZUKU -> if (dhizuku) return PrivilegeMode.DHIZUKU
        PrivilegeMode.NONE, null -> Unit // fall through to auto
    }
    return when {
        root -> PrivilegeMode.ROOT
        shizuku -> PrivilegeMode.SHIZUKU
        dhizuku -> PrivilegeMode.DHIZUKU
        else -> PrivilegeMode.NONE
    }
}
