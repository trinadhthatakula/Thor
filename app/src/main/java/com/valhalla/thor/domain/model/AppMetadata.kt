// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

data class AppMetadata(
    val label: String,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val iconPath: String?,
    val permissions: List<String> = emptyList()
)
