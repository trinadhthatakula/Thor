// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * Domain port for the GET_USAGE_STATS app-op that [StorageStatsProvider] depends on. Covers
 * only the check/grant half; the Settings deep-link stays on the concrete `UsageAccessManager`
 * because it returns an `Intent`, which has no place in a domain signature.
 */
interface UsageAccessGate {
    /** True when this process currently holds the Usage Access app-op. */
    fun isGranted(): Boolean

    /** Best-effort silent grant through the active privilege gateway; returns the *verified* result. */
    suspend fun tryGrantViaPrivilege(): Boolean

    /** One best-effort auto-grant per process, latched only after a grant actually succeeds. */
    suspend fun maybeAutoGrant()
}
