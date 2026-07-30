// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.annotation.StringRes
import com.valhalla.thor.R

sealed interface FilterType {
    data object Source : FilterType
    data object State : FilterType {
        val types = listOf(
            "All", "Active", "Frozen", "Suspended"
        )
    }

    /**
     * Filter by what an app is *allowed* to do — "show me everything that can use the microphone".
     *
     * Unlike [Source] and [State], the chip values here are not a fixed list and are not derivable
     * from [AppInfo]: nothing in the app model records permissions, so the set is built on demand
     * from `PackageManager` while this filter is selected. See `PermissionRepository.buildPermissionIndex`
     * for the sweep and [PermissionIndex] for what it produces.
     */
    data object Permission : FilterType
}

val filterTypes = listOf(
    FilterType.State,
    FilterType.Source,
    FilterType.Permission
)

/**
 * The label for the filter *category*, as shown in the filter sheet.
 *
 * A string resource rather than a literal: these three were hardcoded English while every chip
 * below them was already localised, so a non-English user saw "Active State" sitting on top of
 * "Activa / Congelada / Suspendida". Adding a fourth untranslated one would have widened that gap.
 */
@StringRes
fun FilterType.asGeneralName(): Int = when (this) {
    FilterType.State -> R.string.filter_type_state
    FilterType.Source -> R.string.filter_type_source
    FilterType.Permission -> R.string.filter_type_permission
}
