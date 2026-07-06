package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
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

    // --- Work Mode ---
    suspend fun setPrivilegeMode(mode: PrivilegeMode?)

    // --- Localization ---
    suspend fun setLanguage(language: String?)

    // --- Export ---
    suspend fun setExportDirUri(uri: String?)

    // --- Auto Freeze ---
    suspend fun setAutoFreezeEnabled(enabled: Boolean)
    suspend fun setAddFreezerToLauncher(enabled: Boolean)
    suspend fun setFreezerMode(mode: FreezerMode)

    // --- Freezer Prompts ---
    suspend fun setHasShownDisabledAppsPrompt(hasShown: Boolean)

    // --- Support Developer Prompt ---
    suspend fun setHasShownSupportDeveloperPrompt(hasShown: Boolean)

    // --- App Redirection & Animations ---
    suspend fun setDetailedViewEnabled(enabled: Boolean)
    suspend fun setAnimationIntensity(intensity: AnimationIntensity)

    // --- Grid/List View ---
    suspend fun setAppListIsGrid(isGrid: Boolean)
    suspend fun setFreezerIsGrid(isGrid: Boolean)
    suspend fun toggleAppListIsGrid()
    suspend fun toggleFreezerIsGrid()

    // --- Extensions ---
    suspend fun setExtensionsUnlocked(unlocked: Boolean)
}
