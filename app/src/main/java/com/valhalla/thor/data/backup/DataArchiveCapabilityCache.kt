// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.RootAvailabilityProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/**
 * Capability details for data backup and restore.
 */
data class DataArchiveCapability(
    val isSupported: Boolean,
    val canReadPrivateData: Boolean,
) {
    fun supportedClasses(): Set<DataClass> =
        if (canReadPrivateData) {
            DataClass.entries.toSet()
        } else if (isSupported) {
            setOf(DataClass.EXTERNAL_DATA, DataClass.EXTERNAL_MEDIA)
        } else {
            emptySet()
        }
}

/** A measured answer is reusable only for the same observation revision and active provider. */
@Single
class DataArchiveCapabilityCache(
    private val probe: AppDataProbe,
    private val privilegeState: PrivilegeStateProvider,
    private val rootAvailability: RootAvailabilityProvider,
) {
    private data class CacheKey(val revision: Long, val provider: PrivilegeMode)
    private val mutex = Mutex()
    private var cached: Pair<CacheKey, DataArchiveCapability>? = null

    suspend fun capability(): DataArchiveCapability = mutex.withLock {
        while (true) {
            // Cold start is unknown, not a measured lack of capability.
            val state = privilegeState.state.first { it.isReady }
            val root = rootAvailability.state.value
            val key = CacheKey(root.revision, state.active)
            if (state.active == PrivilegeMode.ROOT && !root.canAdmitRoot) {
                throw RootAdmissionUnavailable(root)
            }
            if (!state.hasAnyPrivilege) {
                return@withLock DataArchiveCapability(isSupported = false, canReadPrivateData = false)
            }
            cached?.takeIf { it.first == key }?.let { return@withLock it.second }
            // Failed probes throw and remain unknown; only completed measurements enter the cache.
            val supported = probe.probeDataArchiveCapability(state.active)
            val privateData = if (supported) probe.probePrivateDataCapability(state.active) else false
            if (currentKey() != key) continue
            val capability = DataArchiveCapability(supported, privateData)
            cached = key to capability
            return@withLock capability
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun isSupported(): Boolean = capability().isSupported
    suspend fun canReadPrivateData(): Boolean = capability().canReadPrivateData
    suspend fun supportedClasses(): Set<DataClass> = capability().supportedClasses()

    private fun currentKey() = CacheKey(rootAvailability.state.value.revision, privilegeState.state.value.active)
}
