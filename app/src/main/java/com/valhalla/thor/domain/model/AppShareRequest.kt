// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Immutable package selection; no provider URIs or prepared files cross the admission boundary. */
data class AppShareRequest(
    val targets: List<AppShareTarget>,
    val format: SharePrepareFormat = SharePrepareFormat.AUTO,
) {
    init {
        require(targets.isNotEmpty()) { "A share request needs at least one target" }
    }
}

data class AppShareTarget(val packageName: String, val label: String?) {
    init {
        require(packageName.isNotBlank() && packageName != "." && packageName != "..")
        require(packageName.none { it == '/' || it == '\\' || it.isISOControl() })
    }
}
