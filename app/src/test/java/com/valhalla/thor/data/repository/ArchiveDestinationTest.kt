// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.THORBAK_EXTENSION
import com.valhalla.thor.domain.model.thorbakFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDestinationTest {

    @Test
    fun `a partial name cannot be mistaken for a finished archive`() {
        val partial = partialName(thorbakFileName("com.example.game", 42))

        // The restore picker filters on the extension, and the launch-time sweep deletes what it
        // finds by this suffix. A partial that still ended in `.thorbak` would be offered as
        // restorable and, worse, a finished archive would be swept.
        assertFalse(partial.endsWith(".$THORBAK_EXTENSION"))
        assertTrue(partial.endsWith(PARTIAL_SUFFIX))
        assertTrue(partial.startsWith("com.example.game-42.$THORBAK_EXTENSION"))
    }

    @Test
    fun `publishing strips exactly the partial suffix`() {
        val finished = thorbakFileName("com.example.game", 42)

        assertEquals(finished, publishedName(partialName(finished)))
    }

    @Test
    fun `a name that is not partial publishes unchanged`() {
        // Defensive: a backend that already writes under the final name (MediaStore's IS_PENDING)
        // must not have its extension chewed off.
        assertEquals("a.thorbak", publishedName("a.thorbak"))
    }

    @Test
    fun `the partial suffix is not a valid archive extension`() {
        // One literal, two consumers — the sweep and the picker. Pinned so a later edit to either
        // cannot quietly make them disagree.
        assertFalse(PARTIAL_SUFFIX.endsWith(THORBAK_EXTENSION))
    }
}
