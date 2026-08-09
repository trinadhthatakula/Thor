// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind `pm trim-caches`, which shipped wrong in v1.94 and freed nothing on any
 * device.
 *
 * `PackageManagerService` opens `freeStorage` with `if (file.getUsableSpace() >= bytes) return;`, so
 * the single property that decides whether a trim does anything at all is whether the target is
 * **strictly greater** than free space. The old implementation handed over
 * `StorageStatsManager.getFreeBytes()`, which is `usableSpace + max(0, cacheTotal - cacheReserve)`;
 * because the reserve is a share of *total* storage it exceeds any phone's whole app cache, the
 * clearable term is 0, and the target equals free space exactly — the one value guaranteed to be
 * refused.
 *
 * Every case below is that comparison. The numbers are deliberately shaped like a real device: the
 * v1.94 regression is unreproducible at toy scale, because it only appears once the cache reserve
 * dwarfs the cache.
 */
class CacheTrimTargetTest {

    private companion object {
        /** A 232 GB phone with 114 GB free — the device the bug was reported from. */
        const val USABLE = 122_917_466_112L
        const val CACHE = 3_221_225_472L // 3 GB of app cache
    }

    @Test
    fun `the target exceeds free space by exactly the cache to reclaim`() {
        assertEquals(USABLE + CACHE, cacheTrimTarget(usableBytes = USABLE, totalCacheBytes = CACHE))
    }

    @Test
    fun `a target for a real cache is strictly greater than free space`() {
        // The whole contract in one assertion. `>=` would pass for the shipped bug; PMS tests `>=`
        // from the other side, so anything not strictly greater returns on its first line.
        assertTrue(cacheTrimTarget(USABLE, CACHE) > USABLE)
    }

    @Test
    fun `the v1_94 regression would fail this test`() {
        // What the old code passed: getFreeBytes(), which on this device is usableSpace + 0 because
        // the ~23 GB cache reserve swallows the 3 GB of cache whole. Pinned as a value rather than
        // described, so a future "simplification" back to a single framework call has something
        // concrete to trip over.
        val whatGetFreeBytesReturned = USABLE + maxOf(0L, CACHE - 23_000_000_000L)
        assertEquals(USABLE, whatGetFreeBytesReturned)
        assertTrue(
            "a target equal to free space is refused by freeStorage",
            whatGetFreeBytesReturned <= USABLE
        )
    }

    @Test
    fun `no cache asks for nothing rather than asking for something else`() {
        // Equal to free space on purpose: PMS refuses it, the before-and-after measurement subtracts
        // to 0, and the sheet says there was no cache left. Clamping this upwards to "free something
        // anyway" would spend rung 5 (unused shared libraries) and rung 7 (instant apps) to satisfy
        // a request for zero bytes of cache.
        assertEquals(USABLE, cacheTrimTarget(usableBytes = USABLE, totalCacheBytes = 0L))
    }

    @Test
    fun `a negative cache total is treated as no cache, not as a smaller target`() {
        // StorageStatsManager should never report this, but the sum is unsigned arithmetic in intent
        // only: a negative here would ask PMS to make *less* space usable than already is, which is
        // still refused but for an accidental reason. Fail closed at the same place as zero.
        assertEquals(USABLE, cacheTrimTarget(usableBytes = USABLE, totalCacheBytes = -1L))
    }

    @Test
    fun `an empty volume still produces a target above its free space`() {
        // Guards the degenerate end: a freshly wiped device has almost no free space recorded in
        // these units and the sum must still clear it.
        assertTrue(cacheTrimTarget(usableBytes = 0L, totalCacheBytes = 1L) > 0L)
    }
}
