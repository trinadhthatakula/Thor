// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow

enum class PrivilegeExecutionLane { INTERACTIVE, ARCHIVE, SWEEP }

@JvmInline
value class PrivilegeCommandClass(val value: String) {
    init {
        require(value.isNotBlank()) { "Command class must not be blank" }
        require(value.none(Char::isISOControl)) {
            "Command class must not contain ISO control characters"
        }
    }
}

data class PrivilegeExecutionContext(
    val lane: PrivilegeExecutionLane = PrivilegeExecutionLane.INTERACTIVE,
    val commandClass: PrivilegeCommandClass = PrivilegeCommandClass("interactive.command"),
    val packageName: String? = null,
    val workRequestId: UUID? = null,
    val sweepRequestId: UUID? = null,
    val commandTimeout: Duration? = null,
)

object PrivilegeExecutionTimeouts {
    val INTERACTIVE_ADMISSION: Duration = Duration.ZERO
    val SWEEP_ADMISSION: Duration = 2.seconds
    val ARCHIVE_ADMISSION: Duration = 5.seconds
    val SWEEP_COMMAND: Duration = 30.seconds
}

enum class PackageOperationOwner {
    ARCHIVE_BACKUP,
    ARCHIVE_RESTORE,
    FREEZE,
    UNFREEZE,
    CLEAR_CACHE,
    CLEAR_DATA,
    REINSTALL,
    FORCE_STOP,
    UNINSTALL,
    OTHER_MUTATION,
}

sealed interface PackageLeaseResult<out T> {
    data class Acquired<T>(val value: T) : PackageLeaseResult<T>
    data class Busy(val owner: PackageOperationOwner) : PackageLeaseResult<Nothing>
}

enum class RootLaneMode { ISOLATED, DEGRADED }

data class RootLaneStatus(
    val lane: PrivilegeExecutionLane,
    val mode: RootLaneMode,
    val activeCommandClass: PrivilegeCommandClass? = null,
    val fallbackOwner: PrivilegeExecutionLane? = null,
)

interface RootLaneStatusSource {
    val statuses: StateFlow<Map<PrivilegeExecutionLane, RootLaneStatus>>
}

sealed class PrivilegeExecutionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PackageOperationBusy(val owner: PackageOperationOwner) :
    PrivilegeExecutionException("Package operation busy: $owner")

class ShellLaneUnavailable(val lane: PrivilegeExecutionLane, cause: Throwable? = null) :
    PrivilegeExecutionException("Root shell lane unavailable: $lane", cause)

class ShellLaneBusy(val owner: PrivilegeExecutionLane) :
    PrivilegeExecutionException("Root shell lane busy: $owner")

class ShellLaneDegraded(val lane: PrivilegeExecutionLane, cause: Throwable? = null) :
    PrivilegeExecutionException("Root shell lane degraded: $lane", cause)

class ShellTransportDied(val lane: PrivilegeExecutionLane, cause: Throwable? = null) :
    PrivilegeExecutionException("Root shell transport died: $lane", cause)

class ShellCommandTimedOut(val commandClass: PrivilegeCommandClass) :
    PrivilegeExecutionException("Root command timed out: ${commandClass.value}")

class ReinstallPostconditionFailed(val packageName: String) :
    PrivilegeExecutionException("Fix Store postcondition failed for $packageName")

class ShellCommandCancelled(
    val commandClass: PrivilegeCommandClass,
    cause: CancellationException,
) : CancellationException("Root command cancelled: ${commandClass.value}") {
    init {
        initCause(cause)
    }
}

class SweepInputMissing(requestId: String?) :
    PrivilegeExecutionException("Sweep input missing: ${requestId ?: "request id"}")
