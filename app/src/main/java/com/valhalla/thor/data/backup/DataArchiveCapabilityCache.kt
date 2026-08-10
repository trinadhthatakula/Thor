// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/**
 * "Can this device back up app data?", answered once per privilege state.
 *
 * The probe is a shell round trip through the gateway, and the backup entry point asks on every
 * sheet open. Keyed on the whole [PrivilegeState] rather than on a TTL: `PrivilegeManager.refresh()`
 * landing a new state *is* the invalidation, so there is no second path to keep in sync and no
 * window where a freshly granted root still reads as unsupported.
 */
@Single
class DataArchiveCapabilityCache(
    private val probe: AppDataProbe,
    private val privilegeState: PrivilegeStateProvider,
) {

    private val mutex = Mutex()

    private var cached: Pair<PrivilegeState, Boolean>? = null

    suspend fun isSupported(): Boolean {
        // Await the first resolved state rather than reading the raw snapshot. `state.value` is the
        // default on cold start: `isReady = false, active = NONE` — `hasAnyPrivilege` would be false
        // and we'd return false immediately, even on a rooted device, until both the privilege probe
        // and the first DataStore emission have landed. `isReady` is set exactly once that has
        // happened, distinguishing "not probed yet" from "probed, nothing available". The same fix
        // lives in BulkFreezeRunner.kt:368 for the same snapshot-read bug.
        val state = privilegeState.state.first { it.isReady }
        // No surface to probe through. Shelling out would raise a `su` prompt on a device where the
        // user granted nothing — and the answer is derived, not measured, so it is not cached.
        if (!state.hasAnyPrivilege) return false

        mutex.withLock {
            cached?.let { (key, value) -> if (key == state) return value }
            val supported = probe.probeDataArchiveCapability()
            cached = state to supported
            return supported
        }
    }
}
