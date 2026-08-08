// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.annotation.StringRes
import com.valhalla.thor.R
import kotlinx.serialization.Serializable

@Serializable
enum class SortBy {
    NAME,
    SIZE,
    INSTALL_DATE,
    LAST_UPDATED,
    VERSION_CODE,
    VERSION_NAME,
    TARGET_SDK_VERSION,
    MIN_SDK_VERSION;

    /**
     * The label for this sort key, as shown in the filter sheet's **Sort** tab.
     *
     * A string resource rather than a literal, for the same reason [FilterType.asGeneralName] is
     * one: these eight sat one tab away from the filter categories, which were already localised,
     * so a French or Arabic user read eight English words in an otherwise translated sheet.
     */
    @StringRes
    fun asGeneralName(): Int = when (this) {
        NAME -> R.string.sort_by_name
        SIZE -> R.string.sort_by_size
        INSTALL_DATE -> R.string.sort_by_install_date
        LAST_UPDATED -> R.string.sort_by_last_updated
        VERSION_CODE -> R.string.sort_by_version_code
        VERSION_NAME -> R.string.sort_by_version_name
        TARGET_SDK_VERSION -> R.string.sort_by_target_sdk
        MIN_SDK_VERSION -> R.string.sort_by_min_sdk
    }
}

fun SortBy.isDateBased(): Boolean = this == SortBy.INSTALL_DATE || this == SortBy.LAST_UPDATED

fun SortBy.isVersionBased(): Boolean = this == SortBy.VERSION_CODE || this == SortBy.VERSION_NAME

fun SortBy.isSdkBased(): Boolean =
    this == SortBy.TARGET_SDK_VERSION || this == SortBy.MIN_SDK_VERSION

fun SortBy.isNameBased(): Boolean = this == SortBy.NAME

/**
 * No `asGeneralName()` here on purpose: the order row draws its own two chips from
 * `R.string.ascending` / `R.string.descending` and its own two arrows from
 * `R.drawable.arrow_upward` / `arrow_downward`, so the label, icon, flip and angle helpers this
 * enum used to carry had no caller at all. Translating four dead functions would have bought
 * nothing; they are gone instead.
 */
@Serializable
enum class SortOrder {
    ASCENDING,
    DESCENDING
}