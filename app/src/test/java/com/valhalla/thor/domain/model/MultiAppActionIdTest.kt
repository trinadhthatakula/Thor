// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAppActionIdTest {
    @Test
    fun `defaults preserve each existing toolbar order`() {
        assertEquals(
            listOf(
                "REINSTALL", "FREEZE", "UNFREEZE", "SUSPEND", "UNSUSPEND", "ADD_TO_PROFILES",
                "CLEAR_CACHE", "SHARE", "EXPORT", "UNINSTALL", "FORCE_STOP",
            ),
            MultiAppActionLayout.APP_LIST.defaultOrder.map { it.name },
        )
        assertEquals(
            listOf(
                "FREEZE", "UNFREEZE", "ADD_TO_PROFILES", "SAVE_AS_PROFILE", "REMOVE_FROM_FREEZER",
                "SHARE", "EXPORT", "UNINSTALL",
            ),
            MultiAppActionLayout.FREEZER.defaultOrder.map { it.name },
        )
        assertFalse(MultiAppActionId.entries.any { it.name == "CLOSE" })
    }

    @Test
    fun `absent empty or unknown saved layouts use defaults`() {
        for (layout in MultiAppActionLayout.entries) {
            for (saved in listOf(null, emptyList(), listOf("", "FUTURE_ACTION"))) {
                assertEquals(layout.defaultOrder, layout.fromSavedNamesOrDefault(saved))
            }
            assertTrue(layout.fromSavedHiddenNames(null).isEmpty())
            assertTrue(layout.fromSavedHiddenNames(emptySet()).isEmpty())
        }
    }

    @Test
    fun `saved order preserves valid choice and appends missing actions once`() {
        for (layout in MultiAppActionLayout.entries) {
            val otherContext = if (layout == MultiAppActionLayout.APP_LIST) {
                "REMOVE_FROM_FREEZER"
            } else {
                "REINSTALL"
            }
            val result = layout.fromSavedNamesOrDefault(
                listOf(" SHARE ", "FUTURE_ACTION", "SHARE", otherContext, "FREEZE", ""),
            )

            assertEquals(listOf(MultiAppActionId.SHARE, MultiAppActionId.FREEZE), result.take(2))
            assertEquals(
                layout.defaultOrder.filterNot { it == MultiAppActionId.SHARE || it == MultiAppActionId.FREEZE },
                result.drop(2),
            )
            assertEquals(result.size, result.toSet().size)
        }
    }

    @Test
    fun `complete custom order round trips without changing it`() {
        for (layout in MultiAppActionLayout.entries) {
            val reordered = layout.defaultOrder.reversed()
            assertEquals(reordered, layout.fromSavedNamesOrDefault(reordered.map { it.name }))
        }
    }

    @Test
    fun `hidden choices are restricted to the selected context`() {
        val saved = setOf("SHARE", "REINSTALL", "REMOVE_FROM_FREEZER", "FUTURE_ACTION", "CLOSE")

        assertEquals(
            setOf(MultiAppActionId.SHARE, MultiAppActionId.REINSTALL),
            MultiAppActionLayout.APP_LIST.fromSavedHiddenNames(saved),
        )
        assertEquals(
            setOf(MultiAppActionId.SHARE, MultiAppActionId.REMOVE_FROM_FREEZER),
            MultiAppActionLayout.FREEZER.fromSavedHiddenNames(saved),
        )
    }

    @Test
    fun `every customizable action can be hidden without storing Close`() {
        for (layout in MultiAppActionLayout.entries) {
            assertEquals(
                layout.defaultOrder.toSet(),
                layout.fromSavedHiddenNames(layout.defaultOrder.mapTo(mutableSetOf()) { it.name }),
            )
        }
    }

    @Test
    fun `preference helpers select the requested layout`() {
        val appList = MultiAppActionLayout.APP_LIST.defaultOrder.reversed()
        val freezer = MultiAppActionLayout.FREEZER.defaultOrder.reversed()
        val prefs = UserPreferences(
            appListMultiActionsOrder = appList,
            hiddenAppListMultiActions = setOf(MultiAppActionId.REINSTALL),
            freezerMultiActionsOrder = freezer,
            hiddenFreezerMultiActions = setOf(MultiAppActionId.REMOVE_FROM_FREEZER),
        )

        assertEquals(appList, prefs.multiAppActionsOrder(MultiAppActionLayout.APP_LIST))
        assertEquals(freezer, prefs.multiAppActionsOrder(MultiAppActionLayout.FREEZER))
        assertEquals(setOf(MultiAppActionId.REINSTALL), prefs.hiddenMultiAppActions(MultiAppActionLayout.APP_LIST))
        assertEquals(setOf(MultiAppActionId.REMOVE_FROM_FREEZER), prefs.hiddenMultiAppActions(MultiAppActionLayout.FREEZER))
    }
}
