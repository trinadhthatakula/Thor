// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

data class AppInstallable(
    val name: String,
    val apkPath: String,
    val isDebuggable: Boolean
)

