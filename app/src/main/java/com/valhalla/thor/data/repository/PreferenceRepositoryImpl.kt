// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.LocalKeys
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import org.koin.core.annotation.Single
import java.io.IOException

private const val TAG = "PreferenceRepository"

/**
 * Latches — for the life of the process — once the settings file has been thrown away and replaced.
 *
 * File-scoped rather than a field on [PreferenceRepositoryImpl] because the handler below is
 * captured by the `preferencesDataStore` delegate, which is a property of `Context` and has no way
 * to reach the repository instance. Nothing resets it: the replacement has already happened, and
 * every read afterwards succeeds, so the fact that the values are not the user's is only knowable
 * from here.
 */
private val settingsFileReplaced = MutableStateFlow(false)

/**
 * The settings store — the one in the Auto Backup allowlist.
 *
 * [ReplaceFileCorruptionHandler] rather than the default handler, which is `NoOpCorruptionHandler`
 * and *rethrows*. Nothing ever rewrites the file after that, so an unreadable
 * `thor_preferences.preferences_pb` rethrows on every read for the life of the install — and since
 * this file is the one that travels in cloud backup and device transfer, a partial restore can put
 * a brand-new device into that state before the user has opened Thor once. Replacing the bad file
 * with an empty one costs the user their settings; not replacing it costs them the app.
 *
 * What makes that trade acceptable rather than merely cheaper is [settingsFileReplaced]: one of the
 * settings in this file is the app lock, and a replacement drops it to `false`. Dropping a lock the
 * user deliberately armed *silently* is precisely what `SecurityViewModel` is written not to do, so
 * the loss is recorded here, carried on [UserPreferences.settingsLost], and said out loud.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "thor_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Logger.e(TAG, "thor_preferences was unreadable; replacing it with an empty file", it)
        settingsFileReplaced.value = true
        emptyPreferences()
    }
)

/**
 * The second store, for state that describes **this install** rather than what the user chose.
 *
 * Thor's backup ruleset is an allowlist naming exactly one path, `datastore/thor_preferences
 * .preferences_pb` — so a store with any other name is excluded from Auto Backup by construction,
 * with no ruleset change and no `BackupAgent`. That is the entire point of it: a value in here
 * cannot arrive on a device that never produced it.
 *
 * What belongs here is a fact *about* something that is itself excluded from backup — the Room
 * database, principally. `has_shown_disabled_apps_prompt` is the case that forced this: it means
 * "we have already offered to import the frozen apps we found", which is a statement about the
 * freezer watchlist, and the watchlist does not travel. Restored into an install whose watchlist is
 * empty, it switched off the one affordance built to rebuild that watchlist, silently. See
 * `docs/follow-ups/restored-prompt-flag-suppresses-watchlist-recovery.md`.
 *
 * A user *setting* does not belong here, however local it feels — settings are what the backup is
 * for.
 *
 * Corruption-handled for the same reason as [dataStore]: this file cannot arrive corrupted from a
 * restore, but an interrupted write or a bad block can still leave it unreadable, and the default
 * handler would then rethrow forever. No equivalent of [settingsFileReplaced] here — the one flag
 * this file holds falls back to "we have not offered yet", which re-offers the recovery prompt
 * rather than withholding anything.
 */
private val Context.localState: DataStore<Preferences> by preferencesDataStore(
    name = "thor_local_state",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Logger.e(TAG, "thor_local_state was unreadable; replacing it with an empty file", it)
        emptyPreferences()
    }
)

@Single(binds = [PreferenceRepository::class])
class PreferenceRepositoryImpl(
    private val context: Context
) : PreferenceRepository {

    internal object Keys {
        // App List
        val SORT_BY = stringPreferencesKey("sort_by")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val FILTER_TYPE = stringPreferencesKey("filter_type")
        val SELECTED_FILTER = stringPreferencesKey("selected_filter")
        val SHOW_REINSTALL_ALL = booleanPreferencesKey("show_reinstall_all")

        // Home tiles
        val SHOW_INSTALLER_TILE = booleanPreferencesKey("show_installer_tile")
        val SHOW_EXTENSIONS_TILE = booleanPreferencesKey("show_extensions_tile")

        // Theme
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val USE_AMOLED = booleanPreferencesKey("use_amoled")

        // Security
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")

        // Work Mode
        val PRIVILEGE_MODE = stringPreferencesKey("privilege_mode")

        // Localization
        val LANGUAGE = stringPreferencesKey("language")

        // Export
        val EXPORT_DIR_URI = stringPreferencesKey("export_dir_uri")

        // Auto Freeze
        val AUTO_FREEZE = booleanPreferencesKey("auto_freeze")
        val ADD_FREEZER_TO_LAUNCHER = booleanPreferencesKey("add_freezer_to_launcher")
        val FREEZER_MODE = stringPreferencesKey("freezer_mode")

        // Freezer Prompts
        //
        // Write-to-delete only. This key lived here until 1.93; it now lives in [LocalKeys], and
        // the copy left behind in a shipped install is removed the next time the flag is written.
        // Reading it again would undo the fix — a restored `true` here is exactly the stale value
        // that suppressed the watchlist recovery prompt.
        val LEGACY_DISABLED_APPS_PROMPT = booleanPreferencesKey("has_shown_disabled_apps_prompt")

        // Support Developer Prompt
        val HAS_SHOWN_SUPPORT_DEVELOPER_PROMPT = booleanPreferencesKey("has_shown_support_developer_prompt")

        // Animations
        val ANIMATION_INTENSITY = stringPreferencesKey("animation_intensity")

        // Grid/List View
        val APP_LIST_IS_GRID = booleanPreferencesKey("app_list_is_grid")
        val FREEZER_IS_GRID = booleanPreferencesKey("freezer_is_grid")

        // Extensions
        val EXTENSIONS_UNLOCKED = booleanPreferencesKey("extensions_unlocked")
        val EXTENSION_CONSENT_ACCEPTED = booleanPreferencesKey("extension_consent_accepted")

        // Auto Reinstall
        val AUTO_REINSTALL_ENABLED = booleanPreferencesKey("auto_reinstall_enabled")
    }

    /** Keys in [localState] — see that store's doc for what earns a place here. */
    internal object LocalKeys {
        /** "We have already offered to import the frozen apps we found." A fact about the watchlist. */
        val HAS_SHOWN_DISABLED_APPS_PROMPT = booleanPreferencesKey("has_shown_disabled_apps_prompt")
    }

    override val userPreferences: Flow<UserPreferences> =
        userPreferencesFlow(context.dataStore.data, context.localState.data)

    // --- App List ---

    override suspend fun updateAppSort(sortBy: SortBy) {
        context.dataStore.edit { it[Keys.SORT_BY] = sortBy.name }
    }

    override suspend fun updateAppSortOrder(sortOrder: SortOrder) {
        context.dataStore.edit { it[Keys.SORT_ORDER] = sortOrder.name }
    }

    override suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String) {
        context.dataStore.edit {
            // An exhaustive `when` rather than the old `if (State) … else "SOURCE"`: that shape
            // silently wrote SOURCE for anything new, so adding a third filter would have persisted
            // the wrong one with nothing to catch it. This form stops compiling instead.
            it[Keys.FILTER_TYPE] = when (filterType) {
                FilterType.State -> "STATE"
                FilterType.Source -> "SOURCE"
                FilterType.Permission -> "PERMISSION"
            }
            it[Keys.SELECTED_FILTER] = selectedFilter
        }
    }

    override suspend fun setReinstallAllCardVisibility(isVisible: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_REINSTALL_ALL] = isVisible }
    }

    override suspend fun setInstallerTileVisibility(isVisible: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_INSTALLER_TILE] = isVisible }
    }

    override suspend fun setExtensionsTileVisibility(isVisible: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_EXTENSIONS_TILE] = isVisible }
    }

    // --- Theme ---

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setUseAmoled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_AMOLED] = enabled }
    }

    // --- Security ---

    override suspend fun setBiometricLock(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BIOMETRIC_LOCK] = enabled }
    }

    // --- Work Mode ---

    override suspend fun setPrivilegeMode(mode: PrivilegeMode?) {
        context.dataStore.edit {
            if (mode == null) it.remove(Keys.PRIVILEGE_MODE)
            else it[Keys.PRIVILEGE_MODE] = mode.name
        }
    }

    override suspend fun setLanguage(language: String?) {
        context.dataStore.edit {
            if (language == null) it.remove(Keys.LANGUAGE)
            else it[Keys.LANGUAGE] = language
        }
    }

    override suspend fun setExportDirUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.EXPORT_DIR_URI)
            else it[Keys.EXPORT_DIR_URI] = uri
        }
    }

    override suspend fun setAutoFreezeEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.AUTO_FREEZE] = enabled
        }
    }

    override suspend fun setFreezerMode(mode: FreezerMode) {
        context.dataStore.edit {
            it[Keys.FREEZER_MODE] = mode.name
        }
    }

    override suspend fun setAddFreezerToLauncher(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.ADD_FREEZER_TO_LAUNCHER] = enabled
        }
    }

    override suspend fun setHasShownDisabledAppsPrompt(hasShown: Boolean) {
        context.localState.edit {
            it[LocalKeys.HAS_SHOWN_DISABLED_APPS_PROMPT] = hasShown
        }
        // Sweep up the pre-1.93 copy on the way past. Nothing reads it any more, so this is tidiness
        // rather than correctness — but leaving it in the backed-up file leaves a loaded gun for
        // whoever next adds a read of that key. This path runs at most a handful of times per
        // install, so the extra write is not worth guarding against.
        context.dataStore.edit {
            it.remove(Keys.LEGACY_DISABLED_APPS_PROMPT)
        }
    }

    override suspend fun setHasShownSupportDeveloperPrompt(hasShown: Boolean) {
        context.dataStore.edit {
            it[Keys.HAS_SHOWN_SUPPORT_DEVELOPER_PROMPT] = hasShown
        }
    }

    override suspend fun setAnimationIntensity(intensity: AnimationIntensity) {
        context.dataStore.edit {
            it[Keys.ANIMATION_INTENSITY] = intensity.name
        }
    }

    override suspend fun setAppListIsGrid(isGrid: Boolean) {
        context.dataStore.edit {
            it[Keys.APP_LIST_IS_GRID] = isGrid
        }
    }

    override suspend fun setFreezerIsGrid(isGrid: Boolean) {
        context.dataStore.edit {
            it[Keys.FREEZER_IS_GRID] = isGrid
        }
    }

    override suspend fun toggleAppListIsGrid() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.APP_LIST_IS_GRID] ?: true
            prefs[Keys.APP_LIST_IS_GRID] = !current
        }
    }

    override suspend fun toggleFreezerIsGrid() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FREEZER_IS_GRID] ?: true
            prefs[Keys.FREEZER_IS_GRID] = !current
        }
    }

    // --- Extensions ---

    override suspend fun setExtensionsUnlocked(unlocked: Boolean) {
        context.dataStore.edit { it[Keys.EXTENSIONS_UNLOCKED] = unlocked }
    }

    override suspend fun setExtensionConsentAccepted(accepted: Boolean) {
        context.dataStore.edit { it[Keys.EXTENSION_CONSENT_ACCEPTED] = accepted }
    }

    override suspend fun setAutoReinstallEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_REINSTALL_ENABLED] = enabled }
    }

    override suspend fun getInstallerArg(): String {
        return if (userPreferences.first().autoReinstallEnabled) " -i com.android.vending" else ""
    }
}

/**
 * The [PreferenceRepository.userPreferences] stream, with the two store flows passed in so the
 * composition is reachable from a plain JVM test.
 *
 * Each side is guarded **before** the [combine], not after: a `catch` downstream of the combine
 * would end the whole stream on the first failure, taking the healthy store down with the broken
 * one. Guarded individually, an unreadable settings file degrades to defaults while `localState`
 * keeps emitting, and vice versa.
 *
 * [settingsFileReplaced] is read at map time rather than combined in as a third flow. It can only
 * be set by the corruption handler, which runs *while producing* the first `settings` emission, so
 * by the time this transform runs the answer is already final — and combining it would open a race
 * where the first snapshot claims the settings are intact and a second one immediately corrects it.
 */
internal fun userPreferencesFlow(
    settings: Flow<Preferences>,
    local: Flow<Preferences>,
    settingsReplaced: StateFlow<Boolean> = settingsFileReplaced
): Flow<UserPreferences> =
    combine(
        settings.guardedRead("thor_preferences"),
        local.guardedRead("thor_local_state")
    ) { prefs, localPrefs ->
        prefs.preferences.toUserPreferences(localPrefs.preferences)
            .copy(settingsLost = prefs.degraded || settingsReplaced.value)
    }

/**
 * One snapshot out of a store, and whether it is the user's or something this file made up.
 *
 * The two cannot be told apart from the [Preferences] alone — an empty store and a store that could
 * not be read look identical — and one of the values in there is the app lock, so "made up" has to
 * survive as far as [UserPreferences.settingsLost].
 */
internal data class StoreRead(val preferences: Preferences, val degraded: Boolean)

/** Retries before a read is written off. Backs off over about half a second in total. */
private const val READ_RETRIES = 3L
private const val READ_RETRY_DELAY_MS = 100L

/**
 * Degrade a store's `.data` to an empty snapshot when the read fails, rather than letting it reach
 * the ~20 places that collect [PreferenceRepository.userPreferences].
 *
 * The `corruptionHandler` on each store above is the primary fix and the one that actually heals
 * the install; this covers what it cannot. It only fires for `CorruptionException` — an
 * unparseable file — so a read that fails for any other IO reason still throws, and a corrupt file
 * the handler could not *replace* (no space, a read-only volume) rethrows the original by design.
 *
 * Retry first, because degrading is **terminal**: `catch` emits once and the upstream is then
 * complete, and none of the collectors re-subscribe — `SecurityViewModel` shares this with
 * `SharingStarted.Eagerly`, which does not restart a finished upstream, and the rest are plain
 * `collect` loops that simply end. So a single transient failure — low storage, an EIO, a read
 * attempted before credential-encrypted storage is unlocked — would pin the whole process to values
 * the user never chose while the file on disk is perfectly intact. Three re-reads cost nothing on a
 * healthy store and are free on a permanently broken one, where the corruption handler has already
 * had its go by the time the first attempt fails.
 *
 * Only [IOException] is swallowed. Anything else, `CancellationException` above all, is rethrown:
 * a cancelled collector must not be handed a fabricated defaults emission on its way out. That
 * distinction is what the tests aim at, which is why this is `internal` rather than private —
 * asserted through [userPreferencesFlow] it would be asserted through `combine`'s own handling of
 * a cancelled child instead.
 */
internal fun Flow<Preferences>.guardedRead(storeName: String): Flow<StoreRead> =
    retryWhen { cause, attempt ->
        (cause is IOException && attempt < READ_RETRIES).also { retrying ->
            if (retrying) delay(READ_RETRY_DELAY_MS * (attempt + 1))
        }
    }
        .map { StoreRead(it, degraded = false) }
        .catch { e ->
            if (e !is IOException) throw e
            Logger.e(TAG, "$storeName could not be read; falling back to the defaults", e)
            emit(StoreRead(emptyPreferences(), degraded = true))
        }

/**
 * Pure mapping from already-read [Preferences] snapshots to [UserPreferences].
 * Extracted from the [PreferenceRepositoryImpl.userPreferences] Flow so it is unit-testable
 * on plain JVM (no Android / DataStore access). Every field mirrors the prior inline mapping.
 *
 * The receiver is the backed-up settings store; [local] is the per-install one. Two snapshots
 * rather than one because which file a value came out of is the whole distinction — see
 * [PreferenceRepositoryImpl] and the `localState` store above. [local] defaults to empty so a
 * caller that only cares about settings need not conjure one.
 */
internal fun Preferences.toUserPreferences(
    local: Preferences = emptyPreferences()
): UserPreferences {
    val prefs = this

    val sortBy = prefs[Keys.SORT_BY]
        ?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }
        ?: SortBy.NAME

    val sortOrder = prefs[Keys.SORT_ORDER]
        ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
        ?: SortOrder.ASCENDING

    // Falls through to Source for an unknown token, so a preferences file written by a *newer*
    // Thor (or a corrupted one) degrades to the default filter instead of failing to read.
    val filterType = when (prefs[Keys.FILTER_TYPE]) {
        "STATE" -> FilterType.State
        "PERMISSION" -> FilterType.Permission
        else -> FilterType.Source
    }

    val themeMode = prefs[Keys.THEME_MODE]
        ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
        ?: ThemeMode.SYSTEM

    val privilegeMode = prefs[Keys.PRIVILEGE_MODE]
        ?.let { runCatching { PrivilegeMode.valueOf(it) }.getOrNull() }

    val animationIntensity = prefs[Keys.ANIMATION_INTENSITY]
        ?.let { runCatching { AnimationIntensity.valueOf(it) }.getOrNull() }
        ?: AnimationIntensity.MEDIUM

    val appListIsGrid = prefs[Keys.APP_LIST_IS_GRID] ?: true
    val freezerIsGrid = prefs[Keys.FREEZER_IS_GRID] ?: true
    val freezerMode = prefs[Keys.FREEZER_MODE]
        ?.let { runCatching { FreezerMode.valueOf(it) }.getOrNull() }
        ?: FreezerMode.FREEZE

    return UserPreferences(
        appSortBy = sortBy,
        appSortOrder = sortOrder,
        appFilterType = filterType,
        appSelectedFilter = prefs[Keys.SELECTED_FILTER] ?: "All",
        showReinstallAllCard = prefs[Keys.SHOW_REINSTALL_ALL] ?: true,
        showInstallerTile = prefs[Keys.SHOW_INSTALLER_TILE] ?: true,
        showExtensionsTile = prefs[Keys.SHOW_EXTENSIONS_TILE] ?: true,
        themeMode = themeMode,
        useDynamicColor = prefs[Keys.USE_DYNAMIC_COLOR] ?: false,
        useAmoled = prefs[Keys.USE_AMOLED] ?: false,
        biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false,
        preferredPrivilegeMode = privilegeMode,
        language = prefs[Keys.LANGUAGE],
        autoFreezeEnabled = prefs[Keys.AUTO_FREEZE] ?: false,
        freezerMode = freezerMode,
        addFreezerToLauncher = prefs[Keys.ADD_FREEZER_TO_LAUNCHER] ?: false,
        // From `local`, never from `prefs`: a `true` in the settings file is either a pre-1.93
        // leftover or a restored one, and both describe a watchlist this install may not have.
        hasShownDisabledAppsPrompt =
            local[LocalKeys.HAS_SHOWN_DISABLED_APPS_PROMPT] ?: false,
        hasShownSupportDeveloperPrompt = prefs[Keys.HAS_SHOWN_SUPPORT_DEVELOPER_PROMPT] ?: false,
        animationIntensity = animationIntensity,
        appListIsGrid = appListIsGrid,
        freezerIsGrid = freezerIsGrid,
        extensionsUnlocked = prefs[Keys.EXTENSIONS_UNLOCKED] ?: false,
        extensionConsentAccepted = prefs[Keys.EXTENSION_CONSENT_ACCEPTED] ?: false,
        autoReinstallEnabled = prefs[Keys.AUTO_REINSTALL_ENABLED] ?: false,
        exportDirUri = prefs[Keys.EXPORT_DIR_URI]
    )
}
