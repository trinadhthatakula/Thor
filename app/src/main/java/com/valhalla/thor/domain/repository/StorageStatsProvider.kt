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
}
