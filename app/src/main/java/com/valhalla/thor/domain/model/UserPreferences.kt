package com.valhalla.thor.domain.model

data class UserPreferences(
    // App List Sorting & Filtering
    val appSortBy: SortBy = SortBy.NAME,
    val appSortOrder: SortOrder = SortOrder.ASCENDING,
    val appFilterType: FilterType = FilterType.Source,
    val appSelectedFilter: String = "All",

    // Home Screen Config
    val showReinstallAllCard: Boolean = true
)