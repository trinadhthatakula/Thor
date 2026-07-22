// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSortingTest {

    private fun app(pkg: String, size: Long?) = AppInfo(packageName = pkg, installSize = size)

    @Test
    fun size_ascending_putsNullsFirstThenSmallestToLargest() {
        val apps = listOf(app("b", 200), app("a", null), app("c", 100))
        val sorted = sortApps(apps, SortBy.SIZE, SortOrder.ASCENDING).map { it.packageName }
        assertEquals(listOf("a", "c", "b"), sorted)
    }

    @Test
    fun size_descending_putsLargestFirstNullsLast() {
        val apps = listOf(app("b", 200), app("a", null), app("c", 100))
        val sorted = sortApps(apps, SortBy.SIZE, SortOrder.DESCENDING).map { it.packageName }
        assertEquals(listOf("b", "c", "a"), sorted)
    }

    @Test
    fun name_ascending_stillWorks() {
        val apps = listOf(
            AppInfo(packageName = "p2", appName = "Beta"),
            AppInfo(packageName = "p1", appName = "alpha")
        )
        val sorted = sortApps(apps, SortBy.NAME, SortOrder.ASCENDING).map { it.packageName }
        assertEquals(listOf("p1", "p2"), sorted)
    }
}
