// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

sealed interface FilterType {
    data object Source : FilterType
    data object State : FilterType {
        val types = listOf(
            "All", "Active", "Frozen", "Suspended"
        )
    };


}

val filterTypes = listOf(
    FilterType.State,
    FilterType.Source
)

fun FilterType.asGeneralName() = when (this) {
    FilterType.State -> "Active State"
    FilterType.Source -> "Installation Source"
}




