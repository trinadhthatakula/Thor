package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface PreferenceRepository {

    // Observe all preferences as a single stream
    val userPreferences: Flow<UserPreferences>

    // --- App List ---
    suspend fun updateAppSort(sortBy: SortBy)
    suspend fun updateAppSortOrder(sortOrder: SortOrder)
    suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String)
    suspend fun setReinstallAllCardVisibility(isVisible: Boolean)

    // --- Theme ---
    suspend fun setThemeMode(themeMode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setUseAmoled(enabled: Boolean)

    // --- Security ---
    suspend fun setBiometricLock(enabled: Boolean)
}
