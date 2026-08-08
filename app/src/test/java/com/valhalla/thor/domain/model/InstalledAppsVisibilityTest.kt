// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two rules that keep a lying package scan from destroying the app cache.
 *
 * The bug they pin is not hypothetical. On MIUI/HyperOS, ColorOS, OriginOS, MagicOS and xiaomi.eu
 * `GET_INSTALLED_APPS` is a three-state runtime permission, and with it set to "while in use"
 * backgrounding Thor collapses `getInstalledPackages()` to a near-empty list. The scan loop read
 * every absent package as uninstalled, deleted the Room rows *and* the cached icon PNGs, and the
 * user came back to a list holding only Thor. [scanVerdict] decides whether a scan is allowed to be
 * that destructive; [installedAppsPermissionState] is what stops the fix nagging every Pixel owner
 * about a permission their device has never defined.
 *
 * Both are pure for the usual reason — `PackageManager` is abstract and `:app` has no mocking
 * library by policy — so "a while-in-use grant on a HyperOS device that has just been backgrounded"
 * is nothing more exotic here than a small set and an [InstalledAppsPermission].
 */
class InstalledAppsVisibilityTest {

    /** The cache size from the field report this guard was written against. */
    private val cachedRows = 175

    /** A ROM that defines the permission as the standard requires: requestable at runtime. */
    private fun dangerous() = DeclaredPermission(isDangerous = true, group = null)

    /**
     * A scan of [count] package names, the platform package included unless a test is about it
     * being missing. The names never matter — only how many there are and whether `android` is one
     * of them, which is the whole point of the rule.
     */
    private fun packages(count: Int, withPlatform: Boolean = true): Set<String> = buildSet {
        if (withPlatform && count > 0) add(PLATFORM_PACKAGE)
        var i = 0
        while (size < count) add("com.example.app${i++}")
    }

    /**
     * The rule under test, against the field report's cache size and a first-ever suspect scan
     * unless the case is about one of those. [suspectScans] is last because only the two tolerance
     * tests care about it, and [permission] is named at every call site because it is what most of
     * these cases are actually varying.
     */
    private fun verdict(
        scanned: Set<String>,
        permission: InstalledAppsPermission,
        cachedCount: Int = cachedRows,
        suspectScans: Int = 0,
    ) = scanVerdict(scanned, cachedCount, suspectScans, permission)

    @Test
    fun aDeviceThatDoesNotDefineThePermissionIsUnsupportedNotDenied() {
        // The Pixel case, and the reason this function exists at all. getPermissionInfo throws
        // NameNotFoundException on every AOSP build, arriving here as null. Reporting Denied would
        // put a banner in front of every non-Chinese-ROM user offering to request a permission the
        // OS would silently ignore — and shouldShowRequestPermissionRationale returns a hard false
        // for an unknown permission, so the usual recipe would then call it "permanently denied"
        // and deep-link them to a Settings page with no such toggle on it.
        assertEquals(
            InstalledAppsPermission.Unsupported,
            installedAppsPermissionState(declared = null, isGranted = false)
        )
        // Still Unsupported even if the grant somehow reads true: an undefined permission is not
        // something the user can be asked about, whatever else the device claims.
        assertEquals(
            InstalledAppsPermission.Unsupported,
            installedAppsPermissionState(declared = null, isGranted = true)
        )
    }

    @Test
    fun aPermissionThatIsNotDangerousIsUnsupported() {
        // A ROM declaring the name below `dangerous` settles it at install time; there is no runtime
        // dialog, so requestPermissions would do nothing the user can see. Same ordering rule as
        // runtimeGroupFor — declared alone is never enough.
        assertEquals(
            InstalledAppsPermission.Unsupported,
            installedAppsPermissionState(
                declared = DeclaredPermission(isDangerous = false, group = null),
                isGranted = false
            )
        )
    }

    @Test
    fun aDangerousPermissionReportsTheGrantItActuallyHas() {
        assertEquals(
            InstalledAppsPermission.Granted,
            installedAppsPermissionState(declared = dangerous(), isGranted = true)
        )
        assertEquals(
            InstalledAppsPermission.Denied,
            installedAppsPermissionState(declared = dangerous(), isGranted = false)
        )
    }

    @Test
    fun anEmptyCacheAcceptsEvenACatastrophicScan() {
        // Fresh install: there is nothing to protect, and every rule below would refuse. Were this
        // rule not first, the cache could never take its first rows and the list would stay empty
        // forever — the guard would have become the bug it was written to prevent.
        assertEquals(
            ScanVerdict.Accept,
            verdict(emptySet(), InstalledAppsPermission.Denied, cachedCount = 0)
        )
        assertEquals(
            ScanVerdict.Accept,
            verdict(packages(3), InstalledAppsPermission.Denied, cachedCount = 0)
        )
    }

    @Test
    fun anOrdinaryScanIsBelieved() {
        // The overwhelmingly common path, the ordinary uninstall included: 174 packages against a
        // 175-row cache is one app gone, and pruning it is the whole job of the scan. Note the
        // permission being Denied does not by itself retain anything — it only ever strengthens a
        // scan that already looks wrong.
        assertEquals(ScanVerdict.Accept, verdict(packages(175), InstalledAppsPermission.Granted))
        assertEquals(ScanVerdict.Accept, verdict(packages(174), InstalledAppsPermission.Denied))
        // A scan larger than the cache is several apps installed, not shrinkage.
        assertEquals(ScanVerdict.Accept, verdict(packages(200), InstalledAppsPermission.Denied))
    }

    @Test
    fun aScanMissingThePlatformPackageIsNeverBelieved() {
        // The reported field shape: 68 of 175 packages came back. `android` is visible to every app
        // on every Android version regardless of filtering, so a scan without it did not fail to
        // find the platform — it was not allowed to answer.
        assertEquals(
            ScanVerdict.Retain(RetainReason.PlatformPackageMissing),
            verdict(packages(68, withPlatform = false), InstalledAppsPermission.Denied)
        )
        // And the case that makes identity rather than counting the rule: 120 of 175 clears any
        // "lost more than half" threshold comfortably, so a count-based guard would wave it through
        // and delete 55 apps' rows and icons. Truncation is caught by who is absent, not how many.
        assertEquals(
            ScanVerdict.Retain(RetainReason.PlatformPackageMissing),
            verdict(packages(120, withPlatform = false), InstalledAppsPermission.Denied)
        )
    }

    @Test
    fun anEmptyScanAgainstANonEmptyCacheIsNeverBelieved() {
        // The backgrounded "while in use" case at its worst: accepting this once wipes every row.
        assertEquals(
            ScanVerdict.Retain(RetainReason.EmptyScan),
            verdict(emptySet(), InstalledAppsPermission.Denied)
        )
    }

    @Test
    fun aScanThatLostMoreThanHalfIsSuspect() {
        // 80 of 175 with `android` present: nothing identifies this one as truncated, so the
        // proportion is all there is to go on. Losing more than half in a single scan is not what
        // uninstalling apps one at a time looks like.
        assertEquals(
            ScanVerdict.Retain(RetainReason.Collapsed),
            verdict(packages(80), InstalledAppsPermission.Denied)
        )
        // Exactly half is the boundary and is believed — `size * 2 < cachedCount` is strict, so a
        // 50/50 split stays on the accepting side instead of being retained on every future scan.
        assertEquals(
            ScanVerdict.Accept,
            verdict(packages(88), InstalledAppsPermission.Denied, cachedCount = 176)
        )
    }

    @Test
    fun aDeniedPermissionNeverAcceptsNoMatterHowOftenItRepeats() {
        // The regression test for the actual bug, and the reason the Denied rule is evaluated
        // *before* the tolerance rule. We have a named cause for the shrinkage, so repetition is not
        // corroboration: asking the same question through the same closed gate just gets the same
        // wrong answer again. Swap the two rules and a backgrounded Thor on HyperOS merely takes two
        // extra scans to delete everything.
        listOf(0, 1, SUSPECT_SCAN_TOLERANCE, 99).forEach { suspectScans ->
            assertEquals(
                "an empty scan was accepted after $suspectScans suspect scans",
                ScanVerdict.Retain(RetainReason.EmptyScan),
                verdict(emptySet(), InstalledAppsPermission.Denied, suspectScans = suspectScans)
            )
            assertEquals(
                "a truncated scan was accepted after $suspectScans suspect scans",
                ScanVerdict.Retain(RetainReason.PlatformPackageMissing),
                verdict(
                    packages(68, withPlatform = false),
                    InstalledAppsPermission.Denied,
                    suspectScans = suspectScans
                )
            )
        }
    }

    @Test
    fun theCacheCanStillShrinkWhenNoPermissionGateExplainsIt() {
        // The other half of the guard: it has to be able to stop guarding. A genuine mass uninstall
        // — or a restore of a much smaller backup — on a device where the permission is Unsupported
        // (every Pixel) or Granted produces a real collapse, and a cache that could only ever grow
        // would keep showing rows and icons for apps that are gone.
        val noGate = listOf(InstalledAppsPermission.Unsupported, InstalledAppsPermission.Granted)
        noGate.forEach { permission ->
            assertEquals(
                "$permission accepted the very first suspect scan",
                ScanVerdict.Retain(RetainReason.Collapsed),
                verdict(packages(20), permission)
            )
            assertEquals(
                "$permission accepted before the tolerance was exhausted",
                ScanVerdict.Retain(RetainReason.Collapsed),
                verdict(
                    packages(20),
                    permission,
                    suspectScans = SUSPECT_SCAN_TOLERANCE - 1
                )
            )
            // Enough independent scans have now agreed, with nothing to explain them away.
            assertEquals(
                "$permission never believed a shrinkage that reproduced",
                ScanVerdict.Accept,
                verdict(packages(20), permission, suspectScans = SUSPECT_SCAN_TOLERANCE)
            )
            assertEquals(
                "$permission never believed a shrinkage that reproduced",
                ScanVerdict.Accept,
                verdict(emptySet(), permission, suspectScans = 99)
            )
        }
    }

    @Test
    fun theFirstMatchingReasonIsTheOneReported() {
        // An empty scan is also, arithmetically, a collapsed one and a scan missing the platform
        // package. The reason ends up in a log line a user pastes into an issue, so it has to name
        // the strongest signal rather than whichever rule happens to sit last in the chain.
        assertEquals(
            ScanVerdict.Retain(RetainReason.EmptyScan),
            verdict(emptySet(), InstalledAppsPermission.Granted)
        )
        // Missing platform package outranks Collapsed for the same reason: it says *why* the scan
        // is short, where the proportion only says that it is.
        assertEquals(
            ScanVerdict.Retain(RetainReason.PlatformPackageMissing),
            verdict(packages(5, withPlatform = false), InstalledAppsPermission.Granted)
        )
    }

    // --- prunableWatchlistRows: the second consumer of the same verdict ---

    private val watchlist = setOf("com.example.gone", "com.example.here")

    @Test
    fun aTrustedScanPrunesOnlyTheRowWhosePackageIsMissing() {
        assertEquals(
            setOf("com.example.gone"),
            prunableWatchlistRows(
                watchlist = watchlist,
                scannedPackageNames = packages(40) + "com.example.here",
                verdict = ScanVerdict.Accept
            )
        )
    }

    @Test
    fun aDisbelievedScanPrunesNothingWhateverItsReason() {
        // The whole safety property, one case per reason. Delete the `Accept` check in
        // prunableWatchlistRows and all three of these turn red: on a collapsed scan every one of
        // these rows looks uninstalled, so the user's entire watchlist would go in a single pass —
        // silently, with no undo, and for a device that is working perfectly well.
        for (reason in RetainReason.entries) {
            assertEquals(
                "retained scan (${reason.name}) must not prune",
                emptySet<String>(),
                prunableWatchlistRows(
                    watchlist = watchlist,
                    scannedPackageNames = emptySet(),
                    verdict = ScanVerdict.Retain(reason)
                )
            )
        }
    }

    @Test
    fun anEmptyScanNeverPrunesEvenWhenItWasAccepted() {
        // scanVerdict's first rule accepts unconditionally when the cache is empty, so an empty
        // scan against an empty cache arrives here carrying Accept. That verdict is about the
        // *cache* — it says nothing about the scan, which saw nothing at all. Without this check
        // the collapse bug comes back wearing the gate's own uniform.
        assertEquals(ScanVerdict.Accept, verdict(emptySet(), InstalledAppsPermission.Denied, cachedCount = 0))
        assertEquals(
            emptySet<String>(),
            prunableWatchlistRows(
                watchlist = watchlist,
                scannedPackageNames = emptySet(),
                verdict = ScanVerdict.Accept
            )
        )
    }

    @Test
    fun aRowForAPackageThisDeviceNeverHadIsStillPruned() {
        // The restored-backup case, and the reason this is scoped to the scan rather than to the
        // rows the app cache is dropping. That set is derived from what was cached, and a package
        // that has never been installed here was never cached — so scoping to it would leave this
        // row forever.
        assertEquals(
            setOf("com.example.neverhere"),
            prunableWatchlistRows(
                watchlist = setOf("com.example.neverhere"),
                scannedPackageNames = packages(80),
                verdict = ScanVerdict.Accept
            )
        )
    }

    @Test
    fun anEmptyWatchlistIsNeverAskedToDeleteAnything() {
        // Room builds `IN ()` from an empty collection and SQLite rejects it, so "nothing to do"
        // has to be answerable here rather than at the DAO.
        assertEquals(
            emptySet<String>(),
            prunableWatchlistRows(
                watchlist = emptySet(),
                scannedPackageNames = packages(80),
                verdict = ScanVerdict.Accept
            )
        )
    }
}
