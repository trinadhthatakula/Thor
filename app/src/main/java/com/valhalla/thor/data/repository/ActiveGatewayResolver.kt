// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootAvailabilityState
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.RootAvailabilityProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val ACTIVE_GATEWAY_CACHE_TTL_MS = 3_000L

internal class ActiveGatewayResolver(
    private val preferredMode: suspend () -> PrivilegeMode?,
    private val rootAvailability: RootAvailabilityProvider,
    private val shizukuAvailable: suspend () -> Boolean,
    private val dhizukuAvailable: suspend () -> Boolean,
    private val elapsedRealtimeMs: () -> Long,
    private val cacheTtlMs: Long = ACTIVE_GATEWAY_CACHE_TTL_MS,
) {
    private data class CacheKey(val revision: Long, val preferred: PrivilegeMode?)
    private data class CacheEntry(val key: CacheKey, val mode: PrivilegeMode, val expiresAtMs: Long)

    private val resolutionMutex = Mutex()
    private var cached: CacheEntry? = null

    suspend fun isRootAvailable(): Boolean =
        rootAvailability.awaitInitialObservation().isConfirmedRoot

    /** Root-only actions with unresolved freshness reach admission for a recoverable refusal. */
    suspend fun canSelectRoot(): Boolean {
        val observation = rootAvailability.awaitInitialObservation()
        return observation.routesToRoot() || observation.refreshStatus != RootRefreshStatus.IDLE
    }

    suspend fun resolve(): Result<PrivilegeMode> = resolutionMutex.withLock {
        resultPreservingCancellation {
            while (true) {
                val preferred = preferredMode()
                // An explicitly selected independent provider must not wait for a root prompt.
                val observation = if (preferred == PrivilegeMode.SHIZUKU || preferred == PrivilegeMode.DHIZUKU) {
                    rootAvailability.state.value
                } else {
                    rootAvailability.awaitInitialObservation()
                }
                val key = CacheKey(observation.revision, preferred)
                if (rootAvailability.state.value.revision != key.revision || preferredMode() != preferred) continue
                cached?.takeIf { it.key == key && elapsedRealtimeMs() < it.expiresAtMs }
                    ?.let { return@resultPreservingCancellation Result.success(it.mode) }

                val result = resolveUncached(observation, preferred)
                // A suspended provider probe must not publish or return an older routing decision.
                if (rootAvailability.state.value.revision != key.revision || preferredMode() != preferred) {
                    continue
                }
                result.onSuccess { cached = CacheEntry(key, it, elapsedRealtimeMs() + cacheTtlMs) }
                return@resultPreservingCancellation result
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private suspend fun resolveUncached(
        observation: RootAvailabilityState,
        preferred: PrivilegeMode?,
    ): Result<PrivilegeMode> {
        when (preferred) {
            PrivilegeMode.ROOT -> if (observation.routesToRoot()) return Result.success(PrivilegeMode.ROOT)
            PrivilegeMode.SHIZUKU -> if (shizukuAvailable()) return Result.success(PrivilegeMode.SHIZUKU)
            PrivilegeMode.DHIZUKU -> if (dhizukuAvailable()) return Result.success(PrivilegeMode.DHIZUKU)
            PrivilegeMode.NONE, null -> Unit
        }
        return when {
            observation.routesToRoot() -> Result.success(PrivilegeMode.ROOT)
            shizukuAvailable() -> Result.success(PrivilegeMode.SHIZUKU)
            dhizukuAvailable() -> Result.success(PrivilegeMode.DHIZUKU)
            else -> Result.failure(IllegalStateException(
                "No privileged gateway available (Root, Shizuku or Dhizuku required)",
            ))
        }
    }
}

internal fun RootAvailabilityState.routesToRoot(): Boolean =
    confirmation != RootConfirmation.NON_ROOT
