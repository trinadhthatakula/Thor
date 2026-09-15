// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Force-stop requires the active Root or Shizuku transport; a device owner has no equivalent. */
fun canForceStopApps(state: PrivilegeState): Boolean = state.isReady && when (state.active) {
    PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU -> true
    PrivilegeMode.DHIZUKU, PrivilegeMode.NONE -> false
}
