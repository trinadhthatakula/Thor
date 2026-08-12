// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import com.valhalla.thor.presentation.navigation.ThorRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariants of the eight doors that the compiler does not already enforce.
 *
 * Most of the split's safety is a build gate rather than a test: `SettingsCategoryScreen`'s single
 * `when (row)` is exhaustive over [SettingsRowId], so a setting added to the catalogue and never
 * drawn is a compile error. That gate runs in one direction only — it proves every *row* reaches a
 * branch, and says nothing about whether every *category* reaches a row, whether two categories
 * claim the same route id, or whether a row survives the round trip through
 * [ThorRoute.SettingsCategory]. Those are the three ways this file can be wrong while still
 * compiling, and they are what is asserted here.
 *
 * What this cannot reach, stated plainly: nothing here renders anything. That the index draws all
 * eight categories, that the detail pane resolves against the same scene key as the list pane, and
 * that a focused row is actually scrolled to are Compose behaviour with no JVM seam — a mismatched
 * scene key produces a silently blank pane that neither this test nor lint can see.
 */
class SettingsCatalogTest {

    /**
     * Every door opens onto something.
     *
     * The exhaustive `when` cannot catch this: it fails when a row has no branch, not when a
     * category has no rows. Moving the last row out of a category — which is a one-line edit inside
     * [SettingsRowId] and looks like a reordering — leaves a full-width row on the index, with an
     * icon and a summary line, that opens an empty scrolling list.
     */
    @Test
    fun everyCategoryHasAtLeastOneRow() {
        for (category in SettingsCategory.entries) {
            assertTrue(
                "$category is drawn on the index but has no rows behind it",
                SettingsRowId.rowsIn(category).isNotEmpty()
            )
        }
    }

    /**
     * Each row belongs to exactly one category, and the eight lists together are the whole enum.
     *
     * `groupBy` gives this for free today. It is pinned because the grouping is a cache — the KDoc
     * on `byCategory` invites replacing it — and a filter written by hand can drop a row or repeat
     * one, which shows up as a setting that exists, compiles, has a branch, and is on no screen.
     */
    @Test
    fun theEightCategoriesPartitionEveryRow() {
        val drawn = SettingsCategory.entries.flatMap { SettingsRowId.rowsIn(it) }
        assertEquals(
            "a row is drawn twice or not at all",
            SettingsRowId.entries.size,
            drawn.size
        )
        assertEquals("a row is drawn under two categories", drawn.size, drawn.distinct().size)
        assertEquals(SettingsRowId.entries.toSet(), drawn.toSet())
    }

    /**
     * A category's rows come out in the order they were declared.
     *
     * The enum's own layout is the source of truth for the order settings appear in — there is no
     * separate ordering field to keep in step, which is deliberate — so an implementation of
     * [SettingsRowId.rowsIn] that sorts, or that groups into an unordered map, silently reshuffles
     * every category the day it lands.
     */
    @Test
    fun rowsComeOutInDeclarationOrder() {
        for (category in SettingsCategory.entries) {
            assertEquals(
                "$category rows are out of declaration order",
                SettingsRowId.entries.filter { it.category == category },
                SettingsRowId.rowsIn(category)
            )
        }
    }

    /**
     * No two categories answer to the same route id.
     *
     * [SettingsCategory.fromId] takes the *first* match, so a duplicated id does not throw or fail
     * to compile — it makes one category permanently unreachable, and its index row opens the other
     * one's settings. Copy-pasting an entry is the obvious way in.
     */
    @Test
    fun categoryIdsAreUniqueAndNotBlank() {
        val ids = SettingsCategory.entries.map { it.id }
        assertEquals("two categories share a route id", ids.size, ids.distinct().size)
        for (id in ids) {
            assertTrue("a category has a blank route id", id.isNotBlank())
            assertEquals("a route id carries whitespace: '$id'", id.trim(), id)
        }
    }

    /** Every category is reachable by the id it publishes. */
    @Test
    fun everyCategoryRoundTripsThroughItsId() {
        for (category in SettingsCategory.entries) {
            assertEquals(category, SettingsCategory.fromId(category.id))
        }
    }

    /**
     * An id no build understands resolves to null rather than to a neighbour.
     *
     * This is the restored-back-stack path, and the reason the route carries a string instead of an
     * ordinal: `rememberNavBackStack` is persisted for task restoration and survives an app update,
     * so a stack saved by yesterday's build can name a category today's build removed or renamed.
     * Null is what makes the entry pop itself; an ordinal would resolve, quietly, to whichever
     * category now sits at that position.
     */
    @Test
    fun anUnknownIdResolvesToNothing() {
        assertNull(SettingsCategory.fromId("privacy"))
        assertNull(SettingsCategory.fromId(""))
        assertNull(SettingsCategory.fromId("APPEARANCE"))
        assertNull(SettingsCategory.fromId(" about "))
    }

    /**
     * The focus argument survives the trip out to the route and back.
     *
     * Search results and the interrupted-restore banner deep-link to a single row by putting
     * `SettingsRowId.name` into [ThorRoute.SettingsCategory.focus]; `MainScreen` reads it back with
     * a name lookup over `entries`. The two halves are written in different files and neither
     * mentions the other, so this pins the contract between them — including that the row named
     * really does live in the category the same route asks for, which is what makes the scroll
     * find it.
     */
    @Test
    fun everyRowRoundTripsThroughTheRouteAsAFocusTarget() {
        for (row in SettingsRowId.entries) {
            val route = ThorRoute.SettingsCategory(row.category.id, row.name)
            val category = SettingsCategory.fromId(route.id)
            assertEquals(row.category, category)

            val focused = route.focus?.let { name ->
                SettingsRowId.entries.firstOrNull { it.name == name }
            }
            assertEquals(row, focused)
            assertNotNull(category)
            assertTrue(
                "${row.name} is focusable but is not drawn in ${row.category}",
                SettingsRowId.rowsIn(category!!).contains(row)
            )
        }
    }

    /**
     * A focus name from an older build is dropped, and the category still opens.
     *
     * The same persistence that can name a missing category can name a missing row. Failing to
     * resolve the focus must cost the highlight and nothing else — the route's id half is still
     * good, so refusing to open the category here would turn a cosmetic staleness into a screen the
     * user cannot reach.
     */
    @Test
    fun anUnknownFocusNameDoesNotTakeTheCategoryWithIt() {
        val route = ThorRoute.SettingsCategory(SettingsCategory.ABOUT.id, "TELEMETRY")
        assertEquals(SettingsCategory.ABOUT, SettingsCategory.fromId(route.id))
        assertNull(SettingsRowId.entries.firstOrNull { it.name == route.focus })
    }

    /** Opening a category with no row in mind is the ordinary case, and carries no focus. */
    @Test
    fun aCategoryOpenedFromTheIndexHasNoFocus() {
        assertNull(ThorRoute.SettingsCategory(SettingsCategory.FREEZER.id).focus)
    }
}
