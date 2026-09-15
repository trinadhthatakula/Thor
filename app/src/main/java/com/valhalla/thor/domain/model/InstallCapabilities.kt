// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.domain.repository.InstallMode

/** Device-owner permission policies do not grant the install-time `-g` capability. */
fun supportsInstallTimePermissionGrants(mode: InstallMode): Boolean =
    mode == InstallMode.ROOT || mode == InstallMode.SHIZUKU

fun supportsInstallTimePermissionGrants(mode: PrivilegeMode): Boolean =
    mode == PrivilegeMode.ROOT || mode == PrivilegeMode.SHIZUKU
