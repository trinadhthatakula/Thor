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

    @Test
    fun versionCode_ascending_respectsVersionCodeMajor() {
        // AppInfo.versionCode used to be an Int fed by `packInfo.longVersionCode.toInt()`.
        // longVersionCode packs versionCodeMajor into the HIGH 32 bits, so an app with
        // versionCodeMajor = 1 truncated to 0 and sorted BELOW every ordinary app instead of
        // above it. The operands are chosen so the truncating implementation inverts the order:
        // 1L shl 32 -> 0, 2L shl 32 -> 0, and (1L shl 32) or 5L -> 5.
        val apps = listOf(
            AppInfo(packageName = "major2", versionCode = 2L shl 32),
            AppInfo(packageName = "plain", versionCode = 4210L),
            AppInfo(packageName = "major1", versionCode = (1L shl 32) or 5L)
        )
        val sorted = sortApps(apps, SortBy.VERSION_CODE, SortOrder.ASCENDING).map { it.packageName }
        assertEquals(listOf("plain", "major1", "major2"), sorted)
    }

    @Test
    fun versionCode_ascending_handlesCodesAboveIntMax() {
        // The other half of the truncation: a code above Int.MAX_VALUE wrapped to a NEGATIVE Int
        // and sorted first. 2_147_483_648 is Int.MAX_VALUE + 1, i.e. exactly the wrap point.
        val apps = listOf(
            AppInfo(packageName = "huge", versionCode = 2_147_483_648L),
            AppInfo(packageName = "small", versionCode = 1L)
        )
        val sorted = sortApps(apps, SortBy.VERSION_CODE, SortOrder.ASCENDING).map { it.packageName }
        assertEquals(listOf("small", "huge"), sorted)
    }
}
