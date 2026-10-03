// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.UadEntry
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.UadRecommendation
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.domain.model.uadRecommendation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUadMetadataTest {

    private val app = AppInfo(packageName = "com.example.system", isSystem = true, enabled = false)
    private val recommended = UadEntry("Oem", "Optional vendor service", "Recommended")

    @Test
    fun `Room round trip keeps metadata unread until the current lookup completes`() {
        val cached = AppEntity.fromDomain(app.withUadMetadata(recommended, loadFailed = false)).toDomain()

        assertFalse(cached.isUadLoaded)
        assertNull(cached.uadRecommendation)

        val refreshed = cached.withUadMetadata(recommended, loadFailed = false)
        assertTrue(refreshed.isUadLoaded)
        assertEquals(UadRecommendation.RECOMMENDED, refreshed.uadRecommendation)
        assertEquals(recommended.description, refreshed.bloatDescription)
        assertEquals(cached.enabled, refreshed.enabled)
    }

    @Test
    fun `a completed missing entry becomes Unknown and clears advice from an older scan`() {
        val previous = app.withUadMetadata(recommended, loadFailed = false)
        val refreshed = previous.withUadMetadata(null, loadFailed = false)

        assertTrue(refreshed.isUadLoaded)
        assertNull(refreshed.bloatRecommendation)
        assertNull(refreshed.bloatDescription)
        assertEquals(UadRecommendation.UNKNOWN, refreshed.uadRecommendation)
    }

    @Test
    fun `a failed load cannot classify even a partial or extension supplied recommendation`() {
        val failed = app.withUadMetadata(recommended, loadFailed = true)

        assertTrue(failed.isUadLoaded)
        assertTrue(failed.isUadLoadFailed)
        assertNull(failed.uadRecommendation)
        assertEquals(FreezeTier.BLOCKED, failed.freezeTier)

        val recovered = failed.withUadMetadata(recommended, loadFailed = false)
        assertFalse(recovered.isUadLoadFailed)
        assertEquals(UadRecommendation.RECOMMENDED, recovered.uadRecommendation)
    }
}
