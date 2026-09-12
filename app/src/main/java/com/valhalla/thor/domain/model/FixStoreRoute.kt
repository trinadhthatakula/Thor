// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

enum class FixStoreRoute { PRIVILEGED, UNAVAILABLE }

fun fixStoreRoute(mode: PrivilegeMode): FixStoreRoute = when (mode) {
    PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU -> FixStoreRoute.PRIVILEGED
    PrivilegeMode.NONE, PrivilegeMode.DHIZUKU -> FixStoreRoute.UNAVAILABLE
}
