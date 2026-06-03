package com.valhalla.thor.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import org.koin.core.annotation.Single
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thor_preferences")

@Single(binds = [PreferenceRepository::class])
class PreferenceRepositoryImpl(
    private val context: Context
) : PreferenceRepository {

    private object Keys {
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

        // Auto Freeze
        val AUTO_FREEZE = booleanPreferencesKey("auto_freeze")
    }

    override val userPreferences: Flow<UserPreferences> = context.dataStore.data
        .map { prefs ->
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

            UserPreferences(
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
                autoFreezeEnabled = prefs[Keys.AUTO_FREEZE] ?: false
            )
        }

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

    override suspend fun setAutoFreezeEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.AUTO_FREEZE] = enabled
        }
    }
}
