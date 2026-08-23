// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.ui.unit.dp
import com.valhalla.thor.domain.model.AppGridDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid-density table is the whole feature: four `LazyVerticalGrid`s read it and nothing else
 * decides how a tile is drawn. These are the two things a wrong number does that no compiler catches
 * — squash the icon, or move the rendering of a user who never opened the setting.
 */
class AppGridMetricsTest {

    @Test
    fun defaultReproducesTodaysRendering() {
        // Pinned literally, not derived. Every one of these was a hardcoded dp in AppItemGrid or
        // FreezerAppPickerItem before the table existed; a "tidier" value here is a silent visual
        // change for every user who never touches the new setting.
        val metrics = gridMetricsFor(AppGridDensity.DEFAULT)
        assertEquals(100.dp, metrics.minCellSize)
        assertEquals(56.dp, metrics.iconSize)
        assertEquals(6.dp, metrics.outerPadding)
        assertEquals(16.dp, metrics.innerPadding)
        assertEquals(32.dp, metrics.cornerRadius)
        assertEquals(8.dp, metrics.labelSpacing)
        assertEquals(16.dp, metrics.badgeSize)
    }

    @Test
    fun defaultSelectionTickKeepsIconsOwnDefaultSize() {
        // Both grid ticks used to carry no `.size()` at all and fell back to Icon's 24 dp default.
        // selectionSize is derived (badgeSize * 1.5), so this is the assertion that keeps the
        // derivation honest at the one density where the old value is known.
        assertEquals(24.dp, gridMetricsFor(AppGridDensity.DEFAULT).selectionSize)
    }

    @Test
    fun everyDensityLeavesRoomForItsIcon() {
        // The actual defect this feature exists to avoid. `Modifier.size` is enforceIncoming = true:
        // an icon that does not fit its cell is coerced smaller *silently*, while cornerRadius stays
        // where the table put it, and the tile renders as a pill. A lone minSize preference — which
        // is what the backlog originally asked for — fails this at Compact.
        AppGridDensity.entries.forEach { density ->
            val m = gridMetricsFor(density)
            val available = m.minCellSize - (m.outerPadding * 2f) - (m.innerPadding * 2f)
            assertTrue(
                "$density gives its ${m.iconSize} icon only $available; it will be squashed",
                available >= m.iconSize
            )
        }
    }

    @Test
    fun everyDensityIsPositive() {
        // Note this does NOT guard against a new enum entry: gridMetricsFor is an expression-body
        // `when` with no `else`, so an unmapped entry is a compile error. What it catches is a zero
        // or negative literal, which Compose accepts for padding and which would collapse the tile.
        AppGridDensity.entries.forEach { density ->
            val m = gridMetricsFor(density)
            assertTrue("$density has a non-positive cell", m.minCellSize > 0.dp)
            assertTrue("$density has a non-positive icon", m.iconSize > 0.dp)
            assertTrue("$density has a negative outer padding", m.outerPadding >= 0.dp)
            assertTrue("$density has a negative inner padding", m.innerPadding >= 0.dp)
            assertTrue("$density has a negative corner radius", m.cornerRadius >= 0.dp)
            assertTrue("$density has a negative label gap", m.labelSpacing >= 0.dp)
            assertTrue("$density has a non-positive badge", m.badgeSize > 0.dp)
        }
    }

    @Test
    fun theTableIsMonotonic() {
        // Compact must be smaller than Default in every dimension and Large larger, or the labels
        // lie: a user picking "Compact" and getting a bigger badge has been told the wrong thing.
        val compact = gridMetricsFor(AppGridDensity.COMPACT)
        val default = gridMetricsFor(AppGridDensity.DEFAULT)
        val large = gridMetricsFor(AppGridDensity.LARGE)

        assertTrue("Compact cell is not smaller", compact.minCellSize < default.minCellSize)
        assertTrue("Large cell is not larger", large.minCellSize > default.minCellSize)
        assertTrue("Compact icon is not smaller", compact.iconSize < default.iconSize)
        assertTrue("Large icon is not larger", large.iconSize > default.iconSize)
        assertTrue("Compact badge is not smaller", compact.badgeSize < default.badgeSize)
        assertTrue("Large badge is not larger", large.badgeSize > default.badgeSize)
        assertTrue("Compact corner is not smaller", compact.cornerRadius < default.cornerRadius)
        assertTrue("Large corner is not larger", large.cornerRadius > default.cornerRadius)
    }

    @Test
    fun cornerRadiusNeverExceedsHalfTheTile() {
        // RoundedCornerShape clamps a radius past half the box, so an over-large value does not
        // crash — the tile just silently becomes a circle and stops matching the rest of the grid.
        AppGridDensity.entries.forEach { density ->
            val m = gridMetricsFor(density)
            val tileWidth = m.minCellSize - (m.outerPadding * 2f)
            assertTrue(
                "$density rounds ${m.cornerRadius} on a $tileWidth tile — that is a circle",
                m.cornerRadius <= tileWidth / 2f
            )
        }
    }

    @Test
    fun theSelectionTickStaysInsideTheIcon() {
        // The tick is drawn over the icon's top-right corner. Larger than the icon and it is no
        // longer a corner marker, it is an overlay covering the thing being selected.
        AppGridDensity.entries.forEach { density ->
            val m = gridMetricsFor(density)
            assertTrue(
                "$density draws a ${m.selectionSize} tick over a ${m.iconSize} icon",
                m.selectionSize < m.iconSize
            )
            assertTrue(
                "$density draws the tick no larger than the badge it has to outrank",
                m.selectionSize > m.badgeSize
            )
        }
    }
}
