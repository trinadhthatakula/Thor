// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Android may reuse a user ID after removal. The serial distinguishes those installations. */
data class FreezerUser(val id: Int, val serial: Int, val name: String, val workProfile: Boolean)

internal data class ScopedFreezerApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isDisabled: Boolean,
    val isSuspended: Boolean,
    val canFreeze: Boolean,
    val canRestore: Boolean,
)
