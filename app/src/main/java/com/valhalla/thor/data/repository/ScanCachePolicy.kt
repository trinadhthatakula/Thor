// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.domain.model.RetainReason
import com.valhalla.thor.domain.model.SUSPECT_SCAN_TOLERANCE
import com.valhalla.thor.domain.model.ScanVerdict
import com.valhalla.thor.domain.model.scanVerdict

/**
 * The three decisions `AppRepositoryImpl`'s scan loop takes about its own cache, as pure functions.
 *
 * They were inline in a `callbackFlow` that needs a live `PackageManager`, a Room DAO and a
 * `SharedPreferences` to reach — which is to say they were untestable, and the test that claimed to
 * cover one of them re-implemented the condition inside the test file and could therefore never
 * fail. Extracting them is the seam: the rules live here, the loop reads them, and a change to a
 * rule is a change a test can see.
 *
 * The verdict *rules* stay in the domain — [scanVerdict] and `prunableWatchlistRows` — because they
 * are about whether a scan is honest. What is here is what the data layer adds on top: a flag only
 * the caller can know, a counter only the caller keeps, and a write only the caller performs.
 */

/**
 * The verdict for a scan, given whether it needed the weaker-flags fallback to see anything.
 *
 * A fallback scan skips [scanVerdict]'s rules entirely. None of them can detect that
 * `MATCH_UNINSTALLED_PACKAGES` was dropped — a flags-0 query leaves no trace in its own output, the
 * packages it cannot see being exactly the ones it does not report — and every rule would happily
 * `Accept` the 300 packages such a scan *does* return. Believing it deletes the Room row, the cached
 * icon PNG and the Freezer watchlist row of every uninstall-frozen app on the device, and the
 * watchlist row is the one a later scan cannot repair.
 *
 * This is the only place [RetainReason.VisibilityFallback] is minted; `InstalledAppsVisibilityTest`
 * asserts the other side of that boundary, that no rule in [scanVerdict] can ever produce it.
 */
internal fun scanVerdictFor(
    usedVisibilityFallback: Boolean,
    scannedPackageNames: Set<String>,
    cachedCount: Int,
    consecutiveSuspectScans: Int,
    permission: InstalledAppsPermission,
): ScanVerdict = if (usedVisibilityFallback) {
    ScanVerdict.Retain(RetainReason.VisibilityFallback)
} else {
    scanVerdict(
        scannedPackageNames = scannedPackageNames,
        cachedCount = cachedCount,
        consecutiveSuspectScans = consecutiveSuspectScans,
        permission = permission,
    )
}

/**
 * The suspect-scan counter after [verdict], from its value before it.
 *
 * Three outcomes, and the middle one is the reason this is a function rather than a `++`:
 * - [ScanVerdict.Accept] resets. The device just answered honestly, so nothing is outstanding.
 * - A [RetainReason.VisibilityFallback] retain changes nothing. [SUSPECT_SCAN_TOLERANCE] exists to
 *   let a *genuinely* shrinking device eventually be believed after that many agreeing scans; a
 *   fallback scan is not evidence of shrinkage at all, so spending the budget on it would hand the
 *   third truncated scan the `Accept` the guard was built to withhold. Not a reset either — a
 *   fallback scan is no proof the device is healthy.
 * - Any other retain spends one. That is the tolerance doing its job.
 */
internal fun nextSuspectScanCount(current: Int, verdict: ScanVerdict): Int = when (verdict) {
    ScanVerdict.Accept -> 0
    is ScanVerdict.Retain ->
        if (verdict.reason == RetainReason.VisibilityFallback) current else current + 1
}

/**
 * Whether this scan may record the locale its labels were mapped under.
 *
 * All three conjuncts are load-bearing, and each one is a stale-label bug if dropped:
 * - `forceRefresh` false means the labels were reused from the cache, not re-read from each app's
 *   resources, so this scan mapped nothing and has nothing to attest to.
 * - a [ScanVerdict.Retain] means the scan itself was not believed — it may have seen a fraction of
 *   the installed packages, so most rows still carry the previous language's labels.
 * - a failed `syncCache` means the re-mapped labels never reached Room, so the rows on disk are
 *   still the old ones.
 *
 * Record the key in any of those cases and nothing will ever force-refresh those rows again: the key
 * says they are in the current language and no package event will contradict it. Re-mapping twice
 * costs one rescan; recording too early costs labels that stay wrong until the next language change.
 */
internal fun shouldRecordLabelLocale(
    forceRefresh: Boolean,
    verdict: ScanVerdict,
    syncCacheSucceeded: Boolean,
): Boolean = forceRefresh && verdict == ScanVerdict.Accept && syncCacheSucceeded
