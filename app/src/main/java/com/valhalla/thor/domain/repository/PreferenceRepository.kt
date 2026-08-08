// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.DefaultTab
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

    /**
     * Latches `true` the first time a write to either store is dropped, and stays there.
     *
     * The mirror image of [UserPreferences.settingsLost], which reports the same disk being
     * unreadable. It is deliberately *not* a field on [UserPreferences]: that flow re-emits when a
     * preference changes, and a write that failed changed nothing, so there would be nothing to
     * carry it.
     *
     * A latch rather than an event because there is no useful second sentence. Once the store is
     * refusing writes every subsequent toggle fails too, and one honest notice beats a queue of
     * identical ones. It is cleared only by [acknowledgeSettingsWriteFailure], never by a
     * successful write — a store that has started failing does not recover within a process.
     *
     * The two setters that report their own outcome ([setBiometricLock], [setLanguage]) do not
     * raise it, so their callers can say something specific instead of being talked over.
     */
    val settingsWriteFailed: Flow<Boolean>

    /**
     * Lowers [settingsWriteFailed] once the user has been told.
     *
     * The latch outlives every ViewModel that reads it — it belongs to the repository, which is a
     * singleton — so without this a notice already delivered replays to the next collector. Thor
     * clears its ViewModels without ending its process (Exit finishes the activity), so the next
     * launch would open on "some settings could not be saved" with nothing having failed since.
     *
     * Clearing it does not claim the disk recovered. The next failed write raises it again, which
     * is the point: the latch tracks *an unreported failure*, not *a broken store*.
     */
    fun acknowledgeSettingsWriteFailure()

    // --- App List ---
    suspend fun updateAppSort(sortBy: SortBy)
    suspend fun updateAppSortOrder(sortOrder: SortOrder)
    suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String)
    suspend fun setReinstallAllCardVisibility(isVisible: Boolean)

    // --- Navigation ---
    suspend fun setDefaultTab(tab: DefaultTab)

    // --- Home tiles ---
    suspend fun setInstallerTileVisibility(isVisible: Boolean)
    suspend fun setExtensionsTileVisibility(isVisible: Boolean)

    // --- Theme ---
    suspend fun setThemeMode(themeMode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setUseAmoled(enabled: Boolean)

    // --- Security ---
    /**
     * @return `true` if the new value reached disk.
     *
     * Reported rather than latched onto [settingsWriteFailed], because this is the one preference
     * whose failure the user must hear about in its own words: a dropped `false` leaves the app
     * still locked after Thor has said it turned the lock off, and a dropped `true` leaves it
     * unlocked after Thor has said it armed it. "Some settings could not be saved" does not tell
     * anyone which way their front door is facing.
     */
    suspend fun setBiometricLock(enabled: Boolean): Boolean

    // --- Work Mode ---
    suspend fun setPrivilegeMode(mode: PrivilegeMode?)

    // --- Localization ---
    /**
     * @return `true` if the new value reached disk.
     *
     * Reported rather than latched for the same reason as [setBiometricLock], plus one of its own:
     * the caller applies the locale to the running process straight afterwards. Applying it on a
     * write that never landed gives the user an app that speaks the new language now and reverts on
     * the next launch, which reads as the setting silently un-choosing itself.
     */
    suspend fun setLanguage(language: String?): Boolean

    // --- Export ---
    suspend fun setExportDirUri(uri: String?)

    // --- Auto Freeze ---
    suspend fun setAutoFreezeEnabled(enabled: Boolean)
    suspend fun setAddFreezerToLauncher(enabled: Boolean)
    suspend fun setFreezerMode(mode: FreezerMode)
    suspend fun setSkipRoutineFreezeConfirmation(enabled: Boolean)

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
    suspend fun setAppGridDensity(density: AppGridDensity)

    // --- Extensions ---
    suspend fun setExtensionsUnlocked(unlocked: Boolean)
    suspend fun setExtensionConsentAccepted(accepted: Boolean)

    // --- Auto Reinstall ---
    suspend fun setAutoReinstallEnabled(enabled: Boolean)
    suspend fun getInstallerArg(): String
}
