// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.domain.model.PLATFORM_PACKAGE
import com.valhalla.thor.domain.model.RetainReason
import com.valhalla.thor.domain.model.SUSPECT_SCAN_TOLERANCE
import com.valhalla.thor.domain.model.ScanVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan loop's three decisions about its own cache: [scanVerdictFor], [nextSuspectScanCount] and
 * [shouldRecordLabelLocale].
 *
 * These call the production functions. An earlier version of this file declared a private
 * `shouldRecordLabelLocale` re-implementing the condition and asserted *that*, so every test passed
 * no matter what `AppRepositoryImpl` did — including if the rule were deleted outright. A test whose
 * subject is its own fixture cannot fail, which makes it worse than no test: it reports coverage the
 * code does not have.
 */
class AppRepositoryScanTest {

    private fun packages(count: Int, withPlatform: Boolean = true): Set<String> =
        buildSet {
            if (withPlatform) add(PLATFORM_PACKAGE)
            while (size < count) add("com.example.app$size")
        }

    // --- scanVerdictFor: the fallback flag the domain rules cannot see ---

    @Test
    fun `a fallback scan is retained however healthy it looks`() {
        // The case that makes the flag necessary: 400 packages, the platform package present, the
        // permission granted, no shrinkage — every rule in scanVerdict() accepts this. Only the
        // caller knows MATCH_UNINSTALLED_PACKAGES was dropped, so only the caller can withhold the
        // Accept, and an accepted fallback scan deletes the Freezer row of every uninstall-frozen
        // app on the device.
        assertEquals(
            ScanVerdict.Retain(RetainReason.VisibilityFallback),
            scanVerdictFor(
                usedVisibilityFallback = true,
                scannedPackageNames = packages(400),
                cachedCount = 400,
                consecutiveSuspectScans = 0,
                permission = InstalledAppsPermission.Granted
            )
        )
    }

    @Test
    fun `a fallback scan is retained even with an empty cache`() {
        // scanVerdict()'s first rule accepts unconditionally when there is nothing to protect. The
        // fallback short-circuit sits in front of it, so this must still retain — an empty cache is
        // a reason to trust a scan's *deletions*, and a fallback scan's deletions are the problem.
        assertEquals(
            ScanVerdict.Retain(RetainReason.VisibilityFallback),
            scanVerdictFor(
                usedVisibilityFallback = true,
                scannedPackageNames = packages(3),
                cachedCount = 0,
                consecutiveSuspectScans = 0,
                permission = InstalledAppsPermission.Granted
            )
        )
    }

    @Test
    fun `a fallback scan never runs out of tolerance`() {
        // The tolerance is what lets a genuinely shrinking device eventually be believed. It must
        // never apply here, at any count: a fallback scan repeated ten times is still ten scans that
        // could not see uninstall-frozen packages.
        for (suspectScans in 0..(SUSPECT_SCAN_TOLERANCE + 8)) {
            assertEquals(
                "fallback scan accepted after $suspectScans suspect scan(s)",
                ScanVerdict.Retain(RetainReason.VisibilityFallback),
                scanVerdictFor(
                    usedVisibilityFallback = true,
                    scannedPackageNames = emptySet(),
                    cachedCount = 200,
                    consecutiveSuspectScans = suspectScans,
                    permission = InstalledAppsPermission.Granted
                )
            )
        }
    }

    @Test
    fun `without the fallback the domain rules decide`() {
        assertEquals(
            ScanVerdict.Accept,
            scanVerdictFor(
                usedVisibilityFallback = false,
                scannedPackageNames = packages(200),
                cachedCount = 200,
                consecutiveSuspectScans = 0,
                permission = InstalledAppsPermission.Granted
            )
        )
        assertEquals(
            ScanVerdict.Retain(RetainReason.Collapsed),
            scanVerdictFor(
                usedVisibilityFallback = false,
                scannedPackageNames = packages(20),
                cachedCount = 200,
                consecutiveSuspectScans = 0,
                permission = InstalledAppsPermission.Denied
            )
        )
    }

    // --- nextSuspectScanCount: the tolerance the fallback must not spend ---

    @Test
    fun `an accepted scan resets the tolerance`() {
        assertEquals(0, nextSuspectScanCount(SUSPECT_SCAN_TOLERANCE, ScanVerdict.Accept))
    }

    @Test
    fun `a suspect retain spends one of the tolerance`() {
        for (reason in RetainReason.entries - RetainReason.VisibilityFallback) {
            assertEquals(
                "retain (${reason.name}) must count towards the tolerance",
                3,
                nextSuspectScanCount(2, ScanVerdict.Retain(reason))
            )
        }
    }

    @Test
    fun `a fallback retain neither spends nor resets the tolerance`() {
        // Spending it would hand the third truncated scan the Accept the guard exists to withhold;
        // resetting it would treat a scan Thor did not believe as proof the device is healthy.
        val fallback = ScanVerdict.Retain(RetainReason.VisibilityFallback)
        assertEquals(0, nextSuspectScanCount(0, fallback))
        assertEquals(1, nextSuspectScanCount(1, fallback))
        assertEquals(SUSPECT_SCAN_TOLERANCE, nextSuspectScanCount(SUSPECT_SCAN_TOLERANCE, fallback))
    }

    // --- shouldRecordLabelLocale: the key that stops a re-map ever happening again ---

    @Test
    fun `the locale is recorded when a forced refresh was accepted and synced`() {
        assertTrue(
            shouldRecordLabelLocale(
                forceRefresh = true,
                verdict = ScanVerdict.Accept,
                syncCacheSucceeded = true
            )
        )
    }

    @Test
    fun `a failed cache sync does not record the locale`() {
        // The re-mapped labels never reached Room, so the rows on disk are still the old language.
        assertFalse(
            shouldRecordLabelLocale(
                forceRefresh = true,
                verdict = ScanVerdict.Accept,
                syncCacheSucceeded = false
            )
        )
    }

    @Test
    fun `no retained scan records the locale whatever its reason`() {
        // A retained scan may have seen a fraction of the installed packages, so most rows still
        // carry the previous language. Iterating `entries` so a reason added later is covered.
        for (reason in RetainReason.entries) {
            assertFalse(
                "retained scan (${reason.name}) must not record the locale",
                shouldRecordLabelLocale(
                    forceRefresh = true,
                    verdict = ScanVerdict.Retain(reason),
                    syncCacheSucceeded = true
                )
            )
        }
    }

    @Test
    fun `an unforced scan does not record the locale`() {
        // It reused the cached labels rather than re-reading them, so it mapped nothing.
        assertFalse(
            shouldRecordLabelLocale(
                forceRefresh = false,
                verdict = ScanVerdict.Accept,
                syncCacheSucceeded = true
            )
        )
    }
}
