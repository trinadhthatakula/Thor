package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface PreferenceRepository {

    // Observe all preferences as a stream
    val userPreferences: Flow<UserPreferences>

    // Updates
    suspend fun updateAppSort(sortBy: SortBy)
    suspend fun updateAppSortOrder(sortOrder: SortOrder)
    suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String)
    suspend fun setReinstallAllCardVisibility(isVisible: Boolean)
}