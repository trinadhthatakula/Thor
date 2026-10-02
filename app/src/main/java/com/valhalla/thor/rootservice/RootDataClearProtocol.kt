// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import com.valhalla.thor.data.gateway.userIdOf

/** Stable append-only wire values for the clear-data request and read-only result query. */
object RootDataClearProtocol {
    const val VERSION = 1
    const val MAX_RECORDS = 256
    const val MAX_REQUEST_ID_LENGTH = 36
    const val MAX_PACKAGE_NAME_LENGTH = 255
    const val OBSERVATION_TIMEOUT_MILLIS = 15_000L

    const val STATUS_UNKNOWN = 0
    const val STATUS_CLEARED = 1
    const val STATUS_FAILED = 2
    const val STATUS_REFUSED = 3

    const val DISPATCH_UNKNOWN = 0
    const val DISPATCH_NOT_STARTED = 1
    const val DISPATCH_IN_FLIGHT = 2
    const val DISPATCH_ACCEPTED = 3
    const val DISPATCH_REJECTED = 4
    const val DISPATCH_UNCERTAIN = 5

    const val CALLBACK_NONE = 0
    const val CALLBACK_SUCCEEDED = 1
    const val CALLBACK_FAILED = 2

    const val REASON_NONE = 0
    const val REASON_INVALID_ARGUMENT = 1
    const val REASON_USER_MISMATCH = 2
    const val REASON_REQUEST_ID_CONFLICT = 3
    const val REASON_BUSY = 4
    const val REASON_CAPACITY = 5
    const val REASON_NOT_FOUND = 6
    const val REASON_PREPARATION_FAILED = 7
    const val REASON_DISPATCH_REJECTED = 8
    const val REASON_DISPATCH_EXCEPTION = 9
    const val REASON_WAIT_EXPIRED = 10
    const val REASON_AWAITING_CALLBACK = 11
    const val REASON_CALLBACK_REJECTED = 12
    const val REASON_CALLBACK_FAILED = 13
    const val REASON_STATE_CONTRADICTION = 14
    const val REASON_DEADLINE_BEFORE_DISPATCH = 15
    const val REASON_WORKER_START_FAILED = 16
    const val REASON_IN_PROGRESS = 17
    const val REASON_WAIT_INTERRUPTED = 18
    const val REASON_SELF_TARGET = 19

    private val REQUEST_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun isValidRequestId(value: String): Boolean =
        value.length == MAX_REQUEST_ID_LENGTH && REQUEST_ID.matches(value)

    fun isValidPackageName(value: String): Boolean =
        value.length <= MAX_PACKAGE_NAME_LENGTH && SuspensionReadbackProtocol.isValidPackageIdentity(value)

    fun isKnownReason(reason: Int): Boolean = reason in REASON_NONE..REASON_SELF_TARGET
}

/** Immutable snapshots are copied to each Binder reply; no caller receives a mutable ledger record. */
internal data class RootDataClearSnapshot(
    val requestId: String,
    val daemonInstanceId: String,
    val packageName: String,
    val userId: Int,
    val status: Int = RootDataClearProtocol.STATUS_UNKNOWN,
    val dispatchState: Int = RootDataClearProtocol.DISPATCH_UNKNOWN,
    val callbackState: Int = RootDataClearProtocol.CALLBACK_NONE,
    val reason: Int = RootDataClearProtocol.REASON_NONE,
) {
    fun toParcelable() = RootDataClearResult().also {
        it.protocolVersion = RootDataClearProtocol.VERSION
        it.requestId = requestId
        it.daemonInstanceId = daemonInstanceId
        it.packageName = packageName
        it.userId = userId
        it.status = status
        it.dispatchState = dispatchState
        it.callbackState = callbackState
        it.reason = reason
    }
}

internal fun validateRootDataClearRequest(
    requestId: String?,
    packageName: String?,
    userId: Int,
    callerUid: Int,
): Int = when {
    requestId == null || !RootDataClearProtocol.isValidRequestId(requestId) ||
        packageName == null || !RootDataClearProtocol.isValidPackageName(packageName) ||
        userId < 0 || callerUid < 0 -> RootDataClearProtocol.REASON_INVALID_ARGUMENT
    callerUid != 0 && callerUid != 1_000 && userIdOf(callerUid) != userId ->
        RootDataClearProtocol.REASON_USER_MISMATCH
    else -> RootDataClearProtocol.REASON_NONE
}
