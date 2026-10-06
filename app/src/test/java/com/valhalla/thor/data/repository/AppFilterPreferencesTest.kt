// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import com.valhalla.thor.domain.model.AppFilterPreferences
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UadRecommendation
import com.valhalla.thor.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Upgrade/restore compatibility and writes through the same DataStore transform as the repository. */
class AppFilterPreferencesTest {
    @Test
    fun `new installs start with independent unfiltered profiles`() {
        val restored = emptyPreferences().toUserPreferences()

        for (type in AppListType.entries) {
            assertEquals(AppFilterPreferences(), UserPreferences().appFilterFor(type))
            assertEquals(AppFilterPreferences(), restored.appFilterFor(type))
        }
    }

    @Test
    fun `each absent profile inherits the complete legacy filter`() {
        val legacyFilters = listOf(
            Triple("SOURCE", FilterType.Source, "org.fdroid.fdroid"),
            Triple("STATE", FilterType.State, "Frozen"),
            Triple("PERMISSION", FilterType.Permission, "android.permission-group.CAMERA"),
        )
        for ((token, filterType, selection) in legacyFilters) {
            val restored = preferencesOf(
                Keys.FILTER_TYPE to token,
                Keys.SELECTED_FILTER to selection,
            ).toUserPreferences()

            for (type in AppListType.entries) {
                assertEquals(AppFilterPreferences(filterType, selection), restored.appFilterFor(type))
            }
        }
    }

    @Test
    fun `editing one inherited profile never changes the untouched profile or legacy fallback`() = runTest {
        for (type in AppListType.entries) {
            val store = RecordingDataStore(
                preferencesOf(
                    Keys.FILTER_TYPE to "STATE",
                    Keys.SELECTED_FILTER to "Frozen",
                    Keys.THEME_MODE to ThemeMode.DARK.name,
                ),
            )
            val other = AppListType.entries.single { it != type }

            store.writeAppFilter(type, FilterType.Permission, "android.permission-group.CAMERA")

            assertEquals(1, store.writes.size)
            val written = store.writes.single()
            assertEquals("PERMISSION", written[typeKey(type)])
            assertEquals("android.permission-group.CAMERA", written[selectionKey(type)])
            assertNull(written[typeKey(other)])
            assertNull(written[selectionKey(other)])
            assertEquals("STATE", written[Keys.FILTER_TYPE])
            assertEquals("Frozen", written[Keys.SELECTED_FILTER])
            assertEquals(ThemeMode.DARK.name, written[Keys.THEME_MODE])
            assertEquals(
                AppFilterPreferences(FilterType.Permission, "android.permission-group.CAMERA"),
                written.toUserPreferences().appFilterFor(type),
            )
            assertEquals(
                AppFilterPreferences(FilterType.State, "Frozen"),
                written.toUserPreferences().appFilterFor(other),
            )

            // Even a later edit cannot move the other profile while it still uses legacy fallback.
            store.writeAppFilter(type, FilterType.Source, "com.android.vending")
            assertEquals(
                AppFilterPreferences(FilterType.State, "Frozen"),
                store.current.toUserPreferences().appFilterFor(other),
            )
        }
    }

    @Test
    fun `both scoped profiles override legacy values and survive later writes independently`() = runTest {
        val store = RecordingDataStore(
            preferencesOf(
                Keys.FILTER_TYPE to "PERMISSION",
                Keys.SELECTED_FILTER to "android.permission-group.CAMERA",
                Keys.USER_FILTER_TYPE to "STATE",
                Keys.USER_SELECTED_FILTER to "Frozen",
                Keys.SYSTEM_FILTER_TYPE to "UAD",
                Keys.SYSTEM_SELECTED_FILTER to "Recommended",
            ),
        )
        assertEquals(
            AppFilterPreferences(FilterType.State, "Frozen"),
            store.current.toUserPreferences().userAppFilter,
        )
        assertEquals(
            AppFilterPreferences(FilterType.Uad, "Recommended"),
            store.current.toUserPreferences().systemAppFilter,
        )

        store.writeAppFilter(AppListType.USER, FilterType.State, "Active")
        store.writeAppFilter(AppListType.SYSTEM, FilterType.Uad, "Expert")

        val restored = store.current.toUserPreferences()
        assertEquals(AppFilterPreferences(FilterType.State, "Active"), restored.userAppFilter)
        assertEquals(AppFilterPreferences(FilterType.Uad, "Expert"), restored.systemAppFilter)
        assertEquals(2, store.writes.size)
    }

    @Test
    fun `partial scoped profiles never borrow the other half from legacy settings`() {
        for (type in AppListType.entries) {
            val typeOnly = preferencesOf(
                Keys.FILTER_TYPE to "SOURCE",
                Keys.SELECTED_FILTER to "org.fdroid.fdroid",
                typeKey(type) to "STATE",
            ).toUserPreferences()
            assertEquals(AppFilterPreferences(FilterType.State), typeOnly.appFilterFor(type))

            val selectionOnly = preferencesOf(
                Keys.FILTER_TYPE to "STATE",
                Keys.SELECTED_FILTER to "Frozen",
                selectionKey(type) to "Active",
            ).toUserPreferences()
            assertEquals(AppFilterPreferences(), selectionOnly.appFilterFor(type))
        }
    }

    @Test
    fun `unknown or missing categories reset selection instead of treating a chip as an installer`() {
        for (type in AppListType.entries) {
            val restored = preferencesOf(
                Keys.FILTER_TYPE to "STATE",
                Keys.SELECTED_FILTER to "Frozen",
                typeKey(type) to "FUTURE_CATEGORY",
                selectionKey(type) to "Recommended",
            ).toUserPreferences()
            assertEquals(AppFilterPreferences(), restored.appFilterFor(type))
        }
        for (legacy in listOf(
            preferencesOf(Keys.FILTER_TYPE to "FUTURE_CATEGORY", Keys.SELECTED_FILTER to "Recommended"),
            preferencesOf(Keys.SELECTED_FILTER to "Frozen"),
        )) {
            for (type in AppListType.entries) {
                assertEquals(AppFilterPreferences(), legacy.toUserPreferences().appFilterFor(type))
            }
        }
    }

    @Test
    fun `invalid fixed or empty selections reset to a visible All chip`() = runTest {
        val invalidFilters = listOf(
            Triple("STATE", FilterType.State, "Retired"),
            Triple("UAD", FilterType.Uad, "Safe"),
            Triple("UAD", FilterType.Uad, "RECOMMENDED"),
            Triple("SOURCE", FilterType.Source, " "),
            Triple("PERMISSION", FilterType.Permission, ""),
        )
        for ((token, filterType, selection) in invalidFilters) {
            val restored = preferencesOf(
                Keys.SYSTEM_FILTER_TYPE to token,
                Keys.SYSTEM_SELECTED_FILTER to selection,
            ).toUserPreferences()
            assertEquals(AppFilterPreferences(filterType), restored.systemAppFilter)

            val store = RecordingDataStore()
            store.writeAppFilter(AppListType.SYSTEM, filterType, selection)
            assertEquals("All", store.current[Keys.SYSTEM_SELECTED_FILTER])
            assertEquals(AppFilterPreferences(filterType), store.current.toUserPreferences().systemAppFilter)
        }
    }

    @Test
    fun `UAD can neither restore nor be written as a user-app filter`() = runTest {
        val restored = preferencesOf(
            Keys.USER_FILTER_TYPE to "UAD",
            Keys.USER_SELECTED_FILTER to "Recommended",
            Keys.SYSTEM_FILTER_TYPE to "UAD",
            Keys.SYSTEM_SELECTED_FILTER to "Recommended",
        ).toUserPreferences()
        assertEquals(AppFilterPreferences(), restored.userAppFilter)
        assertEquals(AppFilterPreferences(FilterType.Uad, "Recommended"), restored.systemAppFilter)

        val store = RecordingDataStore()
        store.writeAppFilter(AppListType.USER, FilterType.Uad, "Recommended")
        assertEquals("SOURCE", store.current[Keys.USER_FILTER_TYPE])
        assertEquals("All", store.current[Keys.USER_SELECTED_FILTER])

        val invalidModel = UserPreferences(
            userAppFilter = AppFilterPreferences(FilterType.Uad, "Recommended"),
        )
        assertEquals(AppFilterPreferences(), invalidModel.appFilterFor(AppListType.USER))
    }

    @Test
    fun `every UAD selection round trips under its stable token`() = runTest {
        val store = RecordingDataStore()
        for (selection in listOf("All") + UadRecommendation.entries.map { it.persistedValue }) {
            store.writeAppFilter(AppListType.SYSTEM, FilterType.Uad, selection)

            assertEquals("UAD", store.current[Keys.SYSTEM_FILTER_TYPE])
            assertEquals(selection, store.current[Keys.SYSTEM_SELECTED_FILTER])
            assertEquals(
                AppFilterPreferences(FilterType.Uad, selection),
                store.current.toUserPreferences().systemAppFilter,
            )
            assertEquals(AppFilterPreferences(), store.current.toUserPreferences().userAppFilter)
        }
    }

    @Test
    fun `installer and permission identifiers remain exact even if unavailable on this device`() = runTest {
        for ((type, filterType, selection) in listOf(
            Triple(AppListType.USER, FilterType.Source, "org.example.removed.installer"),
            Triple(AppListType.SYSTEM, FilterType.Permission, "vendor.permission-group.CUSTOM"),
        )) {
            val store = RecordingDataStore()
            store.writeAppFilter(type, filterType, selection)
            assertEquals(
                AppFilterPreferences(filterType, selection),
                store.current.toUserPreferences().appFilterFor(type),
            )
        }
    }

    private fun typeKey(type: AppListType): Preferences.Key<String> = when (type) {
        AppListType.USER -> Keys.USER_FILTER_TYPE
        AppListType.SYSTEM -> Keys.SYSTEM_FILTER_TYPE
    }

    private fun selectionKey(type: AppListType): Preferences.Key<String> = when (type) {
        AppListType.USER -> Keys.USER_SELECTED_FILTER
        AppListType.SYSTEM -> Keys.SYSTEM_SELECTED_FILTER
    }

    private class RecordingDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        val current: Preferences get() = state.value
        val writes = mutableListOf<Preferences>()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also {
                state.value = it
                writes += it
            }
    }
}
