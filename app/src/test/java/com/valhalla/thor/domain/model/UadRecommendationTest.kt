// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UadRecommendationTest {

    @Test
    fun `recognized metadata is case insensitive`() {
        for (recommendation in UadRecommendation.entries.filter { it != UadRecommendation.UNKNOWN }) {
            assertEquals(
                recommendation,
                UadRecommendation.fromRecommendation(recommendation.persistedValue.uppercase()),
            )
        }
    }

    @Test
    fun `missing blank and extension defined advice is unknown rather than recommended`() {
        for (value in listOf(null, "", " \t\n", "Custom", "Safe", "Unknown", " Recommended ", "Unsafe ")) {
            assertEquals(UadRecommendation.UNKNOWN, UadRecommendation.fromRecommendation(value))
        }
    }

    @Test
    fun `preference tokens round trip exactly without treating invalid tokens as Unknown`() {
        for (recommendation in UadRecommendation.entries) {
            assertEquals(
                recommendation,
                UadRecommendation.fromPersistedValue(recommendation.persistedValue),
            )
        }
        for (value in listOf("", "All", "Retired", "recommended", " Recommended ")) {
            assertNull(UadRecommendation.fromPersistedValue(value))
        }
    }
}
