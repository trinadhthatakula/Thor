// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

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

    /**
     * Observe all preferences as a single stream.
     *
     * Never fails on a read: an unreadable store is retried and then degrades to the defaults
     * instead of throwing, so a collector does not need its own `catch`. That is a promise of this
     * interface, not an accident of the implementation — around twenty call sites collect this,
     * several of them during startup, and a throw from any of them is an unrecoverable crash loop.
     *
     * When it does degrade it says so, on [UserPreferences.settingsLost]. Read that before treating
     * a `false` as the user's answer: after a failed read every boolean here is `false`, and one of
     * them arms the app lock.
     */
    val userPreferences: Flow<UserPreferences>

    // --- App List ---
    suspend fun updateAppSort(sortBy: SortBy)
    suspend fun updateAppSortOrder(sortOrder: SortOrder)
    suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String)
    suspend fun setReinstallAllCardVisibility(isVisible: Boolean)

    // --- Home tiles ---
    suspend fun setInstallerTileVisibility(isVisible: Boolean)
    suspend fun setExtensionsTileVisibility(isVisible: Boolean)

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

    // --- Animations ---
    suspend fun setAnimationIntensity(intensity: AnimationIntensity)

    // --- Grid/List View ---
    suspend fun setAppListIsGrid(isGrid: Boolean)
    suspend fun setFreezerIsGrid(isGrid: Boolean)
    suspend fun toggleAppListIsGrid()
    suspend fun toggleFreezerIsGrid()

    // --- Extensions ---
    suspend fun setExtensionsUnlocked(unlocked: Boolean)
    suspend fun setExtensionConsentAccepted(accepted: Boolean)

    // --- Auto Reinstall ---
    suspend fun setAutoReinstallEnabled(enabled: Boolean)
    suspend fun getInstallerArg(): String
}
