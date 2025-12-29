package com.valhalla.thor.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Extension property to create the DataStore singleton
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thor_preferences")

class PreferenceRepositoryImpl(
    private val context: Context
) : PreferenceRepository {

    private object Keys {
        val SORT_BY = stringPreferencesKey("sort_by")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val FILTER_TYPE = stringPreferencesKey("filter_type") // "STATE" or "SOURCE"
        val SELECTED_FILTER = stringPreferencesKey("selected_filter")
        val SHOW_REINSTALL_ALL = booleanPreferencesKey("show_reinstall_all")
    }

    override val userPreferences: Flow<UserPreferences> = context.dataStore.data
        .map { prefs ->
            // Mapper: DataStore -> Domain Model
            val sortBy = try {
                SortBy.valueOf(prefs[Keys.SORT_BY] ?: SortBy.NAME.name)
            } catch (e: Exception) { SortBy.NAME }

            val sortOrder = try {
                SortOrder.valueOf(prefs[Keys.SORT_ORDER] ?: SortOrder.ASCENDING.name)
            } catch (e: Exception) { SortOrder.ASCENDING }

            val filterTypeStr = prefs[Keys.FILTER_TYPE] ?: "SOURCE"
            val filterType = if (filterTypeStr == "STATE") FilterType.State else FilterType.Source

            UserPreferences(
                appSortBy = sortBy,
                appSortOrder = sortOrder,
                appFilterType = filterType,
                appSelectedFilter = prefs[Keys.SELECTED_FILTER] ?: "All",
                showReinstallAllCard = prefs[Keys.SHOW_REINSTALL_ALL] ?: true
            )
        }

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
}