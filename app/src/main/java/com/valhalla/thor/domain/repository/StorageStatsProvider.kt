// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * Domain port for per-package install sizes. Keeps callers free of `StorageStatsManager`,
 * `PackageManager` and `UserHandle` — the concrete impl lives in the data layer and resolves
 * all three from a `Context`. Signatures use only String/primitives, no Android types.
 */
interface StorageStatsProvider {
    /**
     * Total install size (app + data + cache) per package. Packages whose stats cannot be
     * read are omitted rather than reported as zero, so callers can keep a previous value.
     */
    suspend fun installSizes(packages: List<String>): Map<String, Long>

    /**
     * Cache held by one package — internal *and* external — or `null` when it cannot be read.
     *
     * The replacement for the `getAppCacheSize` that used to sit on all three gateways with no
     * caller. That one shelled out to `du` over a single directory, so it under-reported by whatever
     * sat in the device-encrypted and external caches; this reads the same total
     * `StorageStatsManager` shows in Settings, in every privilege mode, from unprivileged code.
     *
     * `null` is not zero, for the reason [totalCacheBytes] gives.
     */
    suspend fun cacheBytes(packageName: String): Long?

    /**
     * Cache held by **every** app in Thor's user — internal and external — or `null` when it
     * cannot be read.
     *
     * This is the one measurement that survives every privilege mode, which is the whole reason it
     * exists: `pm trim-caches` picks its own victims by LRU and reports nothing, so the only honest
     * way to say how much a global clear freed is to read this before and after and subtract. It
     * needs no privilege at all — `StorageStatsService.checkStatsPermission` accepts the
     * `GET_USAGE_STATS` app-op that `UsageAccessManager` already grants and re-verifies — so the
     * same code answers under Root, Shizuku and Dhizuku alike.
     *
     * `null` is not zero. A missing usage-access op and a device with genuinely no cache are
     * different answers, and a caller that renders "freed 0 B" for the first one is lying.
     */
    suspend fun totalCacheBytes(): Long?

    /**
     * The free-space target to hand `pm trim-caches`, or `null` when it cannot be read.
     *
     * Deliberately `StorageStatsManager.getFreeBytes`, which is the *wrong* call for measuring what
     * a clear freed and the *right* one for asking for it — the two uses are inverted, so read this
     * before assuming either. `StorageStatsService.getFreeBytes` returns
     * `path.getUsableSpace() + cacheClearable`: it already counts reclaimable cache as free, so as a
     * measurement its before/after delta cancels to noise. As a *target* that same identity is
     * exactly what is wanted, because it names the byte count at which every clearable cache byte
     * has been reclaimed and not one more.
     *
     * That precision is load-bearing, not tidiness. `PackageManagerService.freeStorage` is an
     * escalating ladder, and app cache is only rungs 4 and 8 of it: an unsatisfiable target walks
     * on to prune unused static shared libraries (rung 5) and uninstall instant apps (rungs 7 and
     * 10), none of which is cache and none of which a button labelled "clear cache" may do. Passing
     * this value lets PMS return at rung 4 or 8. Passing a round number like `100G` does not.
     * (Rungs 2 and 3 — preload caches and parsed APK data — need `FLAG_ALLOCATE_AGGRESSIVE`, which
     * `pm trim-caches` never sets.)
     */
    suspend fun cacheTrimTargetBytes(): Long?
}
