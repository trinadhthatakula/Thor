// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** The filter category and chip remembered independently by each Apps tab. */
data class AppFilterPreferences(
    val filterType: FilterType = FilterType.Source,
    val selectedFilter: String = ALL_FILTER,
) {
    /** Invalid saved choices reset the visible selection as well as the applied filter. */
    fun normalizedFor(appListType: AppListType): AppFilterPreferences {
        if (appListType == AppListType.USER && filterType == FilterType.Uad) {
            return AppFilterPreferences()
        }
        val validSelection = when (filterType) {
            FilterType.Source, FilterType.Permission -> selectedFilter.isNotBlank()
            FilterType.State -> selectedFilter in FilterType.State.types
            FilterType.Uad -> selectedFilter == ALL_FILTER ||
                UadRecommendation.fromPersistedValue(selectedFilter) != null
        }
        return if (validSelection) this else copy(selectedFilter = ALL_FILTER)
    }
}
