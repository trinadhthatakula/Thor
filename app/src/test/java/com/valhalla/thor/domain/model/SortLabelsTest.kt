// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SortLabelsTest {

    @Test
    fun everySortKeyResolvesToARealString() {
        // 0 is what a `@StringRes Int` degrades to if an entry is ever mapped to a missing or
        // deleted resource; `stringResource(0)` throws at composition, i.e. in the sort sheet.
        SortBy.entries.forEach { sortBy ->
            assertNotEquals("$sortBy has no label", 0, sortBy.asGeneralName())
        }
    }

    @Test
    fun noTwoSortKeysShareALabel() {
        // The failure this guards is a copy-paste in the `when`: two entries pointing at the same
        // key reads as a duplicate row in the sheet, and picking either one sorts by whichever the
        // *other* branch meant. Lint cannot see it — the orphaned key still exists, it is just
        // never referenced, and UnusedResources only fires once the key is unreachable entirely.
        val ids = SortBy.entries.map { it.asGeneralName() }
        assertEquals(SortBy.entries.size, ids.toSet().size)
    }

    @Test
    fun sdkKeysAreNotCrossWired() {
        // The only two entries whose resource name is not a lower_snake_case echo of the entry
        // (TARGET_SDK_VERSION -> sort_by_target_sdk, MIN_SDK_VERSION -> sort_by_min_sdk), so they
        // are the pair a rename is most likely to swap without anything else noticing.
        assertEquals(R.string.sort_by_target_sdk, SortBy.TARGET_SDK_VERSION.asGeneralName())
        assertEquals(R.string.sort_by_min_sdk, SortBy.MIN_SDK_VERSION.asGeneralName())
    }
}
