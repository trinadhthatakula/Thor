package com.valhalla.thor.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thor_preferences")

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
        val HAS_SHOWN_DISABLED_APPS_PROMPT = booleanPreferencesKey("has_shown_disabled_apps_prompt")

        // Support Developer Prompt
        val HAS_SHOWN_SUPPORT_DEVELOPER_PROMPT = booleanPreferencesKey("has_shown_support_developer_prompt")

        // App Redirection & Animations
        val USE_DETAILED_VIEW = booleanPreferencesKey("use_detailed_view")
        val ANIMATION_INTENSITY = stringPreferencesKey("animation_intensity")

        // Grid/List View
        val APP_LIST_IS_GRID = booleanPreferencesKey("app_list_is_grid")
        val FREEZER_IS_GRID = booleanPreferencesKey("freezer_is_grid")

        // Extensions
        val EXTENSIONS_UNLOCKED = booleanPreferencesKey("extensions_unlocked")

        // CorePatch — durable "we intentionally turned the package verifier off" marker. Survives a
        // crash/kill so the reconciler can force it back on at next launch (fail-safe self-heal).
        val VERIFIER_INTENTIONALLY_DISABLED = booleanPreferencesKey("verifier_intentionally_disabled")
    }

    override val userPreferences: Flow<UserPreferences> = context.dataStore.data
        .map { it.toUserPreferences() }

    // --- App List ---

    override suspend fun updateAppSort(sortBy: SortBy) {
        context.dataStore.edit { it[Keys.SORT_BY] = sortBy.name }
    }

    override suspend fun updateAppSortOrder(sortOrder: SortOrder) {
        context.dataStore.edit { it[Keys.SORT_ORDER] = sortOrder.name }
    }

    override suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String) {
        context.dataStore.edit {
            it[Keys.FILTER_TYPE] = if (filterType is FilterType.State) "STATE" else "SOURCE"
            it[Keys.SELECTED_FILTER] = selectedFilter
        }
    }

    override suspend fun setReinstallAllCardVisibility(isVisible: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_REINSTALL_ALL] = isVisible }
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
        context.dataStore.edit {
            it[Keys.HAS_SHOWN_DISABLED_APPS_PROMPT] = hasShown
        }
    }

    override suspend fun setHasShownSupportDeveloperPrompt(hasShown: Boolean) {
        context.dataStore.edit {
            it[Keys.HAS_SHOWN_SUPPORT_DEVELOPER_PROMPT] = hasShown
        }
    }

    override suspend fun setDetailedViewEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.USE_DETAILED_VIEW] = enabled
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

    // --- CorePatch ---

    override suspend fun setVerifierIntentionallyDisabled(disabled: Boolean) {
        context.dataStore.edit { it[Keys.VERIFIER_INTENTIONALLY_DISABLED] = disabled }
    }
}

/**
 * Pure mapping from an already-read [Preferences] snapshot to [UserPreferences].
 * Extracted from the [PreferenceRepositoryImpl.userPreferences] Flow so it is unit-testable
 * on plain JVM (no Android / DataStore access). Every field mirrors the prior inline mapping.
 */
internal fun Preferences.toUserPreferences(): UserPreferences {
    val prefs = this

    val sortBy = prefs[Keys.SORT_BY]
        ?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }
        ?: SortBy.NAME

    val sortOrder = prefs[Keys.SORT_ORDER]
        ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
        ?: SortOrder.ASCENDING

    val filterType = when (prefs[Keys.FILTER_TYPE]) {
        "STATE" -> FilterType.State
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

    val useDetailedView = prefs[Keys.USE_DETAILED_VIEW] ?: true
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
        themeMode = themeMode,
        useDynamicColor = prefs[Keys.USE_DYNAMIC_COLOR] ?: false,
        useAmoled = prefs[Keys.USE_AMOLED] ?: false,
        biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false,
        preferredPrivilegeMode = privilegeMode,
        language = prefs[Keys.LANGUAGE],
        autoFreezeEnabled = prefs[Keys.AUTO_FREEZE] ?: false,
        freezerMode = freezerMode,
        addFreezerToLauncher = prefs[Keys.ADD_FREEZER_TO_LAUNCHER] ?: false,
        hasShownDisabledAppsPrompt = prefs[Keys.HAS_SHOWN_DISABLED_APPS_PROMPT] ?: false,
        hasShownSupportDeveloperPrompt = prefs[Keys.HAS_SHOWN_SUPPORT_DEVELOPER_PROMPT] ?: false,
        useDetailedView = useDetailedView,
        animationIntensity = animationIntensity,
        appListIsGrid = appListIsGrid,
        freezerIsGrid = freezerIsGrid,
        extensionsUnlocked = prefs[Keys.EXTENSIONS_UNLOCKED] ?: false,
        exportDirUri = prefs[Keys.EXPORT_DIR_URI],
        verifierIntentionallyDisabled = prefs[Keys.VERIFIER_INTENTIONALLY_DISABLED] ?: false
    )
}
