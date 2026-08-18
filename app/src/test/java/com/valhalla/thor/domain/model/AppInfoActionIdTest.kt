// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoActionIdTest {

    @Test
    fun `default order contains all entries in order`() {
        assertEquals(AppInfoActionId.entries, AppInfoActionId.DEFAULT_ORDER)
        assertEquals(16, AppInfoActionId.DEFAULT_ORDER.size)
    }

    @Test
    fun `fromSavedNamesOrDefault with null or empty returns default order`() {
        assertEquals(AppInfoActionId.DEFAULT_ORDER, AppInfoActionId.fromSavedNamesOrDefault(null))
        assertEquals(AppInfoActionId.DEFAULT_ORDER, AppInfoActionId.fromSavedNamesOrDefault(emptyList()))
    }

    @Test
    fun `fromSavedNamesOrDefault drops unknown names and appends missing entries`() {
        val input = listOf("SETTINGS", "UNKNOWN_FUTURE_ACTION", "SHARE", "OPEN")
        val result = AppInfoActionId.fromSavedNamesOrDefault(input)

        assertEquals(AppInfoActionId.SETTINGS, result[0])
        assertEquals(AppInfoActionId.SHARE, result[1])
        assertEquals(AppInfoActionId.OPEN, result[2])

        // Ensure all missing entries are appended and no duplicates exist
        assertEquals(AppInfoActionId.entries.size, result.size)
        assertEquals(AppInfoActionId.entries.toSet(), result.toSet())
    }

    @Test
    fun `fromSavedHiddenNames parses valid names and ignores unknowns`() {
        val input = setOf("FREEZE", "SUSPEND", "UNKNOWN_ACTION")
        val result = AppInfoActionId.fromSavedHiddenNames(input)

        assertEquals(setOf(AppInfoActionId.FREEZE, AppInfoActionId.SUSPEND), result)
    }

    @Test
    fun `fromSavedHiddenNames returns empty set for null or empty`() {
        assertTrue(AppInfoActionId.fromSavedHiddenNames(null).isEmpty())
        assertTrue(AppInfoActionId.fromSavedHiddenNames(emptySet()).isEmpty())
    }
}
