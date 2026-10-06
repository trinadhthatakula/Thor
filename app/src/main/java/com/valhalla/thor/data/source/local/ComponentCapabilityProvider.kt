// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import com.valhalla.thor.domain.model.ComponentCapability
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.componentCapability
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.RootAvailabilityProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper

/** Component capability belongs to one root-observation revision and selected provider. */
@Single
class ComponentCapabilityProvider(
    private val privilegeState: PrivilegeStateProvider,
    private val rootAvailability: RootAvailabilityProvider,
) {
    private data class CacheKey(val revision: Long, val provider: PrivilegeMode)
    private val mutex = Mutex()
    private var cached: Pair<CacheKey, ComponentCapability>? = null

    internal var readShizukuUid: () -> Int? = { runCatching { ShizukuHelper.uidOrNull() }.getOrNull() }

    suspend fun capability(): ComponentCapability = mutex.withLock {
        while (true) {
            val state = privilegeState.state.first { it.isReady }
            val root = rootAvailability.state.value
            val key = CacheKey(root.revision, state.active)
            if (state.active == PrivilegeMode.ROOT && !root.canAdmitRoot) {
                return@withLock ComponentCapability.None
            }
            cached?.takeIf { it.first == key }?.let { return@withLock it.second }
            val uid = if (state.active == PrivilegeMode.SHIZUKU) readShizukuUid() else null
            if (currentKey() != key) continue
            // An unreadable Binder identity is unknown, not a measured shell-uid limitation.
            if (state.active == PrivilegeMode.SHIZUKU && uid == null) {
                return@withLock ComponentCapability.None
            }
            val capability = componentCapability(state.active, state.isReady, uid)
            cached = key to capability
            return@withLock capability
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun currentKey() = CacheKey(rootAvailability.state.value.revision, privilegeState.state.value.active)
}
