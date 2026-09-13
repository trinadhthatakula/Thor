// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val ACTIVE_GATEWAY_CACHE_TTL_MS = 3_000L

internal class ActiveGatewayResolver(
    private val preferredMode: suspend () -> PrivilegeMode?,
    private val rootAvailable: suspend (PrivilegeExecutionContext) -> Boolean,
    private val shizukuAvailable: suspend () -> Boolean,
    private val dhizukuAvailable: suspend () -> Boolean,
    private val elapsedRealtimeMs: () -> Long,
    private val cacheTtlMs: Long = ACTIVE_GATEWAY_CACHE_TTL_MS,
) {
    private data class CacheEntry(
        val mode: PrivilegeMode,
        val expiresAtMs: Long,
    )

    private val resolutionMutex = Mutex()
    private val rootProbeMutex = Mutex()

    @Volatile
    private var cached: CacheEntry? = null

    suspend fun isRootAvailable(execution: PrivilegeExecutionContext): Boolean =
        rootProbeMutex.withLock { rootAvailable(execution) }

    suspend fun resolve(execution: PrivilegeExecutionContext): Result<PrivilegeMode> {
        freshCachedMode()?.let { return Result.success(it) }

        return resolutionMutex.withLock {
            freshCachedMode()?.let { return@withLock Result.success(it) }

            val result = resultPreservingCancellation { resolveUncached(execution) }
            result.onSuccess { mode ->
                cached = CacheEntry(
                    mode = mode,
                    expiresAtMs = elapsedRealtimeMs() + cacheTtlMs,
                )
            }
            result
        }
    }

    private fun freshCachedMode(): PrivilegeMode? = cached
        ?.takeIf { elapsedRealtimeMs() < it.expiresAtMs }
        ?.mode

    private suspend fun resolveUncached(
        execution: PrivilegeExecutionContext,
    ): Result<PrivilegeMode> {
        when (preferredMode()) {
            PrivilegeMode.ROOT -> if (isRootAvailable(execution)) {
                return Result.success(PrivilegeMode.ROOT)
            }

            PrivilegeMode.SHIZUKU -> if (shizukuAvailable()) {
                return Result.success(PrivilegeMode.SHIZUKU)
            }

            PrivilegeMode.DHIZUKU -> if (dhizukuAvailable()) {
                return Result.success(PrivilegeMode.DHIZUKU)
            }

            PrivilegeMode.NONE, null -> Unit
        }

        return when {
            isRootAvailable(execution) -> Result.success(PrivilegeMode.ROOT)
            shizukuAvailable() -> Result.success(PrivilegeMode.SHIZUKU)
            dhizukuAvailable() -> Result.success(PrivilegeMode.DHIZUKU)
            else -> Result.failure(
                IllegalStateException(
                    "No privileged gateway available (Root, Shizuku or Dhizuku required)",
                ),
            )
        }
    }
}
