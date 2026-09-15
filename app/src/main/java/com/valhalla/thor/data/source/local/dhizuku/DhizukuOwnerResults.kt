// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal const val OWNER_OPERATION_TIMEOUT_MS = 60_000L

/** A request is not success: wait for its package's first callback, or return null on timeout. */
internal suspend fun <T> awaitPackageOperation(
    packageName: String,
    timeoutMillis: Long,
    start: (completed: (String, T) -> Unit) -> Unit,
): T? = withTimeoutOrNull(timeoutMillis) {
    val result = CompletableDeferred<T>()
    start { completedPackage, value ->
        if (completedPackage == packageName) result.complete(value)
    }
    result.await()
}

internal fun setAndVerifyPermissionState(
    granted: Boolean,
    setPolicy: () -> Boolean,
    readPolicyMatches: () -> Boolean,
    readGranted: () -> Boolean?,
): Boolean = setPolicy() && readPolicyMatches() && readGranted() == granted

/** stderr remains visible even when the process also writes normal output. */
internal fun combineProcessOutput(vararg streams: String): String =
    streams.filter { it.isNotBlank() }.joinToString("\n") { it.trimEnd() }

/** Only an affirmative terminal result plus a successful absence read proves removal. */
internal fun uninstallVerified(reportedSuccess: Boolean, installed: Result<Boolean>): Boolean =
    reportedSuccess && installed.getOrNull() == false
