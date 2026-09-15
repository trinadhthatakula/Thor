// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import com.valhalla.thor.domain.model.AppInfoActionId
import com.valhalla.thor.domain.model.MultiAppActionId
import com.valhalla.thor.domain.model.MultiAppActionLayout
import com.valhalla.thor.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAppActionsPreferencesTest {
    @Test
    fun `empty settings preserve both existing toolbars`() {
        val prefs = emptyPreferences().toUserPreferences()

        for (layout in MultiAppActionLayout.entries) {
            assertEquals(layout.defaultOrder, prefs.multiAppActionsOrder(layout))
            assertTrue(prefs.hiddenMultiAppActions(layout).isEmpty())
        }
    }

    @Test
    fun `restored customization drops unknown and other context names`() {
        val prefs = preferencesOf(
            Keys.APP_LIST_MULTI_ACTIONS_ORDER to " SHARE ,REMOVE_FROM_FREEZER,FUTURE,SHARE,FREEZE",
            Keys.HIDDEN_APP_LIST_MULTI_ACTIONS to setOf("REINSTALL", "REMOVE_FROM_FREEZER", "FUTURE"),
            Keys.FREEZER_MULTI_ACTIONS_ORDER to "UNINSTALL,REINSTALL,UNINSTALL,FUTURE",
            Keys.HIDDEN_FREEZER_MULTI_ACTIONS to setOf("SHARE", "REINSTALL", "FUTURE"),
        ).toUserPreferences()

        assertEquals(listOf(MultiAppActionId.SHARE, MultiAppActionId.FREEZE), prefs.appListMultiActionsOrder.take(2))
        assertEquals(MultiAppActionLayout.APP_LIST.defaultOrder.toSet(), prefs.appListMultiActionsOrder.toSet())
        assertEquals(setOf(MultiAppActionId.REINSTALL), prefs.hiddenAppListMultiActions)
        assertEquals(MultiAppActionId.UNINSTALL, prefs.freezerMultiActionsOrder.first())
        assertEquals(MultiAppActionLayout.FREEZER.defaultOrder.toSet(), prefs.freezerMultiActionsOrder.toSet())
        assertEquals(setOf(MultiAppActionId.SHARE), prefs.hiddenFreezerMultiActions)
    }

    @Test
    fun `order and visibility round trip independently for both layouts`() = runTest {
        val store = RecordingDataStore()
        val appListOrder = MultiAppActionLayout.APP_LIST.defaultOrder.reversed()
        val freezerOrder = MultiAppActionLayout.FREEZER.defaultOrder.reversed()

        store.writeMultiAppActionsOrder(MultiAppActionLayout.APP_LIST, appListOrder)
        store.writeMultiAppActionVisibility(MultiAppActionLayout.APP_LIST, MultiAppActionId.SHARE, false)
        store.writeMultiAppActionsOrder(MultiAppActionLayout.FREEZER, freezerOrder)
        store.writeMultiAppActionVisibility(MultiAppActionLayout.FREEZER, MultiAppActionId.UNINSTALL, false)

        val restored = store.current.toUserPreferences()
        assertEquals(appListOrder, restored.appListMultiActionsOrder)
        assertEquals(setOf(MultiAppActionId.SHARE), restored.hiddenAppListMultiActions)
        assertEquals(freezerOrder, restored.freezerMultiActionsOrder)
        assertEquals(setOf(MultiAppActionId.UNINSTALL), restored.hiddenFreezerMultiActions)
        assertEquals(AppInfoActionId.DEFAULT_ORDER, restored.appInfoActionsOrder)
        assertTrue(restored.hiddenAppInfoActions.isEmpty())
    }

    @Test
    fun `writers reconcile malformed order and update the latest hidden set`() = runTest {
        val store = RecordingDataStore()
        val layout = MultiAppActionLayout.FREEZER
        store.writeMultiAppActionsOrder(
            layout,
            listOf(MultiAppActionId.SHARE, MultiAppActionId.REINSTALL, MultiAppActionId.SHARE),
        )
        store.writeMultiAppActionVisibility(layout, MultiAppActionId.SHARE, false)
        store.writeMultiAppActionVisibility(layout, MultiAppActionId.FREEZE, false)
        store.writeMultiAppActionVisibility(layout, MultiAppActionId.SHARE, true)
        val beforeInvalidWrite = store.current
        store.writeMultiAppActionVisibility(layout, MultiAppActionId.REINSTALL, false)

        assertEquals(beforeInvalidWrite, store.current)
        val restored = store.current.toUserPreferences()
        assertEquals(listOf(MultiAppActionId.SHARE) + layout.defaultOrder.filterNot { it == MultiAppActionId.SHARE }, restored.freezerMultiActionsOrder)
        assertEquals(setOf(MultiAppActionId.FREEZE), restored.hiddenFreezerMultiActions)
        assertEquals(4, store.writes.size)
    }

    @Test
    fun `reset removes both selected keys in one edit and preserves unrelated customization`() = runTest {
        for (layout in MultiAppActionLayout.entries) {
            val store = RecordingDataStore(
                preferencesOf(
                    Keys.APP_LIST_MULTI_ACTIONS_ORDER to "SHARE,FREEZE",
                    Keys.HIDDEN_APP_LIST_MULTI_ACTIONS to setOf("SHARE"),
                    Keys.FREEZER_MULTI_ACTIONS_ORDER to "UNINSTALL,SHARE",
                    Keys.HIDDEN_FREEZER_MULTI_ACTIONS to setOf("UNINSTALL"),
                    Keys.APP_INFO_ACTIONS_ORDER to "SETTINGS,OPEN",
                    Keys.HIDDEN_APP_INFO_ACTIONS to setOf("CLEAR_DATA"),
                    Keys.THEME_MODE to ThemeMode.DARK.name,
                ),
            )
            val before = store.current.toUserPreferences()
            val other = MultiAppActionLayout.entries.single { it != layout }

            store.clearMultiAppActionsCustomization(layout)

            assertEquals(1, store.writes.size)
            val after = store.current.toUserPreferences()
            assertEquals(layout.defaultOrder, after.multiAppActionsOrder(layout))
            assertTrue(after.hiddenMultiAppActions(layout).isEmpty())
            assertEquals(before.multiAppActionsOrder(other), after.multiAppActionsOrder(other))
            assertEquals(before.hiddenMultiAppActions(other), after.hiddenMultiAppActions(other))
            assertEquals(before.appInfoActionsOrder, after.appInfoActionsOrder)
            assertEquals(before.hiddenAppInfoActions, after.hiddenAppInfoActions)
            assertEquals(ThemeMode.DARK, after.themeMode)
            if (layout == MultiAppActionLayout.APP_LIST) {
                assertNull(store.current[Keys.APP_LIST_MULTI_ACTIONS_ORDER])
                assertNull(store.current[Keys.HIDDEN_APP_LIST_MULTI_ACTIONS])
            } else {
                assertNull(store.current[Keys.FREEZER_MULTI_ACTIONS_ORDER])
                assertNull(store.current[Keys.HIDDEN_FREEZER_MULTI_ACTIONS])
            }
        }
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
