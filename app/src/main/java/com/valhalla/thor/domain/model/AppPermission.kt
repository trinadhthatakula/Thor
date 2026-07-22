// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

data class AppPermission(
    val name: String,
    val label: String,
    val description: String,
    val group: String?,
    val isGranted: Boolean,
    val isRuntime: Boolean,
    val protectionLevel: Int
)
