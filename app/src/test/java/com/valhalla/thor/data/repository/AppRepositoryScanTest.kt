// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.RetainReason
import com.valhalla.thor.domain.model.ScanVerdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that label locale recording only occurs when an accepted scan's cache
 * sync succeeds without failure.
 */
class AppRepositoryScanTest {

    private fun shouldRecordLabelLocale(
        forceRefresh: Boolean,
        verdict: ScanVerdict,
        syncCacheSucceeded: Boolean
    ): Boolean = forceRefresh && verdict == ScanVerdict.Accept && syncCacheSucceeded

    @Test
    fun `recordLabelLocale is only executed when scan is accepted and cache sync succeeds`() {
        assertTrue(
            shouldRecordLabelLocale(
                forceRefresh = true,
                verdict = ScanVerdict.Accept,
                syncCacheSucceeded = true
            )
        )
    }

    @Test
    fun `recordLabelLocale is skipped when cache sync fails during accepted scan`() {
        assertFalse(
            shouldRecordLabelLocale(
                forceRefresh = true,
                verdict = ScanVerdict.Accept,
                syncCacheSucceeded = false
            )
        )
    }

    @Test
    fun `recordLabelLocale is skipped when scan is retained`() {
        assertFalse(
            shouldRecordLabelLocale(
                forceRefresh = true,
                verdict = ScanVerdict.Retain(RetainReason.Collapsed),
                syncCacheSucceeded = true
            )
        )
    }

    @Test
    fun `recordLabelLocale is skipped when not a forced refresh`() {
        assertFalse(
            shouldRecordLabelLocale(
                forceRefresh = false,
                verdict = ScanVerdict.Accept,
                syncCacheSucceeded = true
            )
        )
    }
}
