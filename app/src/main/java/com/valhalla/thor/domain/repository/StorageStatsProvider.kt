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
     * **`pm trim-caches N` means "ensure N bytes are *usable*", not "free N bytes of cache".** The
     * first line of `FreeStorageHelper.freeStorage` is `if (file.getUsableSpace() >= bytes) return;`
     * — no reserve is added to that comparison — so a target at or below current free space is a
     * silent no-op, and `pm` still exits 0. The target must therefore **exceed** free space by the
     * amount to reclaim: [totalCacheBytes] on top of the volume's usable space, which is why this is
     * a sum and not a single framework call.
     *
     * It used to be `StorageStatsManager.getFreeBytes`, and that shipped in v1.94 as a **guaranteed
     * no-op on real hardware**. `getFreeBytes` is `usableSpace + max(0, cacheTotal - cacheReserve)`,
     * and the reserve is a percentage of *total* storage — tens of gigabytes on a modern phone,
     * always larger than the device's whole app cache — so the clearable term is 0, the target
     * collapses to `usableSpace` exactly, and PMS returns on its first line every time. The
     * measured-zero this produced was indistinguishable from a device with no cache, on root and
     * Shizuku alike. Reasoning that ends in "and not one byte more" is worth suspecting here: the
     * bound that matters is the one PMS actually tests.
     *
     * Bounding it *is* still load-bearing, just at the other end. `freeStorage` is an escalating
     * ladder and app cache is rungs 4 and 8; overshooting walks on to pruning unused static shared
     * libraries (rung 5) and uninstalling instant apps (rungs 7 and 10), none of which is cache.
     * A sum of the two real numbers stops at rung 8. `pm trim-caches 100G` does not. (Rungs 2 and 3
     * — preload caches and parsed APK data — need `FLAG_ALLOCATE_AGGRESSIVE`, which `pm trim-caches`
     * never sets, and rung 6, dexopt output, is `// TODO: Implement` upstream.)
     *
     * Consequence worth stating: this now needs the usage-access op, because [totalCacheBytes] does.
     * Root survives a `null` — its sweep names the directories itself — and Shizuku does not, which
     * is the one place the op is a hard requirement rather than a nicety.
     */
    suspend fun cacheTrimTargetBytes(): Long?
}
