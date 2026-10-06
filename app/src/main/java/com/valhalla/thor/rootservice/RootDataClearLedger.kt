// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.valhalla.thor.rootservice.RootDataClearProtocol as Protocol

internal fun interface RootDataClearCallback {
    fun onCompleted(packageName: String?, succeeded: Boolean, callerUid: Int)
}

/** Preparation must finish constructing the observer and resolving reflection before returning. */
internal fun interface RootDataClearPreparation {
    fun prepare(packageName: String, userId: Int, callback: RootDataClearCallback): PreparedRootDataClear
}

internal fun interface PreparedRootDataClear {
    fun dispatch(): Boolean
}

/**
 * One service-lifetime history, one active clear, and no destructive retries. All retained records
 * remain available until this ledger dies: capacity exhaustion refuses new work before preparation.
 * The caller wait is bounded; Android's Binder dispatch and asynchronous clear are not cancellable.
 * Missing callbacks and dispatch exceptions retain admission. Only a returned dispatch and a valid
 * terminal callback, or proven nonacceptance, can release it. Restart never establishes no effects.
 */
internal class RootDataClearLedger(
    private val preparation: RootDataClearPreparation,
    private val protectedPackageName: String,
    private val observationTimeoutMillis: Long = Protocol.OBSERVATION_TIMEOUT_MILLIS,
    private val maxRecords: Int = Protocol.MAX_RECORDS,
    private val nowNanos: () -> Long = System::nanoTime,
    private val startWorker: (Runnable) -> Unit = { work ->
        Thread(work, "thor-data-clear").apply { isDaemon = true }.start()
    },
    val daemonInstanceId: String = UUID.randomUUID().toString(),
) {
    private val lock = Any()
    private val records = linkedMapOf<RequestKey, Record>()
    private var active: Record? = null

    init {
        require(observationTimeoutMillis in 1..Protocol.OBSERVATION_TIMEOUT_MILLIS)
        require(maxRecords in 1..Protocol.MAX_RECORDS)
        require(Protocol.isValidRequestId(daemonInstanceId))
    }

    fun clear(requestId: String?, packageName: String?, userId: Int, callerUid: Int): RootDataClearSnapshot {
        val invalid = validateRootDataClearRequest(requestId, packageName, userId, callerUid)
        if (invalid != Protocol.REASON_NONE) return refused(requestId, packageName, userId, invalid)
        val id = requireNotNull(requestId)
        val target = requireNotNull(packageName)
        if (target == protectedPackageName) return refused(id, target, userId, Protocol.REASON_SELF_TARGET)

        val record = synchronized(lock) {
            val key = RequestKey(callerUid, id)
            records[key]?.let { existing ->
                return if (existing.packageName == target && existing.userId == userId) {
                    snapshotLocked(existing)
                } else refused(id, target, userId, Protocol.REASON_REQUEST_ID_CONFLICT)
            }
            if (active != null) return refused(id, target, userId, Protocol.REASON_BUSY)
            if (records.size >= maxRecords) return refused(id, target, userId, Protocol.REASON_CAPACITY)
            Record(id, target, userId,
                nowNanos() + TimeUnit.MILLISECONDS.toNanos(observationTimeoutMillis)).also {
                records[key] = it
                active = it
            }
        }

        try {
            startWorker(Runnable { runRequest(record) })
        } catch (_: Throwable) {
            synchronized(lock) {
                // Even a faulty starter that enqueues and then throws cannot dispatch later: the
                // worker checks this abort under the same lock before preparation and invocation.
                record.abortBeforeDispatch = Protocol.REASON_WORKER_START_FAILED
                if (!record.workerEntered) record.workerExited = true
                finishIfTerminalLocked(record)
            }
        }

        try {
            val remaining = record.deadlineNanos - nowNanos()
            if (remaining > 0) record.finished.await(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            synchronized(lock) {
                record.abortBeforeDispatch = Protocol.REASON_WAIT_INTERRUPTED
                record.observationInterrupted = true
            }
            Thread.currentThread().interrupt()
        }
        return synchronized(lock) { snapshotLocked(record) }
    }

    /** Looking up a record never dispatches work or changes its admission. */
    fun query(requestId: String?, packageName: String?, userId: Int, callerUid: Int): RootDataClearSnapshot {
        val invalid = validateRootDataClearRequest(requestId, packageName, userId, callerUid)
        if (invalid != Protocol.REASON_NONE) return refused(requestId, packageName, userId, invalid)
        val id = requireNotNull(requestId)
        val target = requireNotNull(packageName)
        return synchronized(lock) {
            val record = records[RequestKey(callerUid, id)]
                ?: return@synchronized RootDataClearSnapshot(id, daemonInstanceId, target, userId,
                    reason = Protocol.REASON_NOT_FOUND)
            if (record.packageName != target || record.userId != userId) {
                refused(id, target, userId, Protocol.REASON_REQUEST_ID_CONFLICT)
            } else snapshotLocked(record)
        }
    }

    // Called by a Runnable's generated class; internal avoids a SyntheticAccessor bridge.
    internal fun runRequest(record: Record) {
        try {
            synchronized(lock) {
                record.workerEntered = true
                if (record.terminal != null || !mayDispatchLocked(record)) return
            }
            val dispatch = try {
                preparation.prepare(record.packageName, record.userId) { reported, succeeded, caller ->
                    recordCallback(record, reported, succeeded, caller)
                }
            } catch (_: Throwable) {
                synchronized(lock) { record.abortBeforeDispatch = Protocol.REASON_PREPARATION_FAILED }
                return
            }
            synchronized(lock) {
                if (!mayDispatchLocked(record)) return
                record.dispatchState = Protocol.DISPATCH_IN_FLIGHT
            }
            try {
                val accepted = dispatch.dispatch()
                synchronized(lock) {
                    record.dispatchState = if (accepted) Protocol.DISPATCH_ACCEPTED else Protocol.DISPATCH_REJECTED
                }
            } catch (_: Throwable) {
                // Crossing the invocation boundary makes even a thrown SecurityException uncertain.
                // A callback remains evidence in its own field, but cannot turn this into success.
                synchronized(lock) { record.dispatchState = Protocol.DISPATCH_UNCERTAIN }
            }
        } finally {
            synchronized(lock) {
                record.workerExited = true
                finishIfTerminalLocked(record)
            }
        }
    }

    internal fun recordCallback(record: Record, reportedPackage: String?, succeeded: Boolean, callerUid: Int) {
        synchronized(lock) {
            if (record.terminal != null || record.callbackState != Protocol.CALLBACK_NONE) return
            if ((callerUid != 0 && callerUid != 1_000) || reportedPackage != record.packageName ||
                record.dispatchState == Protocol.DISPATCH_NOT_STARTED
            ) {
                record.callbackRejected = true
                return
            }
            record.callbackState = if (succeeded) Protocol.CALLBACK_SUCCEEDED else Protocol.CALLBACK_FAILED
            finishIfTerminalLocked(record)
        }
    }

    private fun mayDispatchLocked(record: Record): Boolean {
        if (record.abortBeforeDispatch != Protocol.REASON_NONE) return false
        if (nowNanos() - record.deadlineNanos >= 0) {
            record.abortBeforeDispatch = Protocol.REASON_DEADLINE_BEFORE_DISPATCH
            return false
        }
        return true
    }

    private fun finishIfTerminalLocked(record: Record) {
        if (!record.workerExited || record.terminal != null) return
        val snapshot = snapshotLocked(record)
        if (snapshot.status == Protocol.STATUS_UNKNOWN) return
        record.terminal = snapshot
        if (active === record) active = null
        record.finished.countDown()
    }

    private fun snapshotLocked(record: Record): RootDataClearSnapshot {
        record.terminal?.let { return it }
        val status: Int
        val reason: Int
        when (record.dispatchState) {
            Protocol.DISPATCH_NOT_STARTED -> {
                status = if (record.workerExited && record.abortBeforeDispatch != Protocol.REASON_NONE) {
                    Protocol.STATUS_REFUSED
                } else Protocol.STATUS_UNKNOWN
                reason = record.abortBeforeDispatch.takeIf { it != Protocol.REASON_NONE }
                    ?: pendingReason(record, Protocol.REASON_IN_PROGRESS)
            }
            Protocol.DISPATCH_IN_FLIGHT -> {
                status = Protocol.STATUS_UNKNOWN
                reason = pendingReason(record, Protocol.REASON_IN_PROGRESS)
            }
            Protocol.DISPATCH_ACCEPTED -> when (record.callbackState) {
                Protocol.CALLBACK_SUCCEEDED -> {
                    status = if (record.workerExited) Protocol.STATUS_CLEARED else Protocol.STATUS_UNKNOWN
                    reason = if (record.workerExited) Protocol.REASON_NONE else Protocol.REASON_IN_PROGRESS
                }
                Protocol.CALLBACK_FAILED -> {
                    status = if (record.workerExited) Protocol.STATUS_FAILED else Protocol.STATUS_UNKNOWN
                    reason = if (record.workerExited) Protocol.REASON_CALLBACK_FAILED else Protocol.REASON_IN_PROGRESS
                }
                else -> {
                    status = Protocol.STATUS_UNKNOWN
                    reason = if (record.callbackRejected) Protocol.REASON_CALLBACK_REJECTED
                        else pendingReason(record, Protocol.REASON_AWAITING_CALLBACK)
                }
            }
            Protocol.DISPATCH_REJECTED -> {
                val contradictory = record.callbackState == Protocol.CALLBACK_SUCCEEDED
                status = if (record.workerExited && !contradictory) Protocol.STATUS_REFUSED else Protocol.STATUS_UNKNOWN
                reason = if (contradictory) Protocol.REASON_STATE_CONTRADICTION else Protocol.REASON_DISPATCH_REJECTED
            }
            else -> {
                status = Protocol.STATUS_UNKNOWN
                reason = Protocol.REASON_DISPATCH_EXCEPTION
            }
        }
        return RootDataClearSnapshot(record.requestId, daemonInstanceId, record.packageName, record.userId,
            status, record.dispatchState, record.callbackState, reason)
    }

    private fun pendingReason(record: Record, ordinary: Int): Int = when {
        record.observationInterrupted -> Protocol.REASON_WAIT_INTERRUPTED
        nowNanos() - record.deadlineNanos >= 0 -> Protocol.REASON_WAIT_EXPIRED
        else -> ordinary
    }

    private fun refused(requestId: String?, packageName: String?, userId: Int, reason: Int) =
        RootDataClearSnapshot(
            requestId = requestId?.takeIf(Protocol::isValidRequestId).orEmpty(),
            daemonInstanceId = daemonInstanceId,
            packageName = packageName?.takeIf(Protocol::isValidPackageName).orEmpty(),
            userId = userId,
            status = Protocol.STATUS_REFUSED,
            dispatchState = Protocol.DISPATCH_NOT_STARTED,
            reason = reason,
        )

    private data class RequestKey(val callerUid: Int, val requestId: String)

    internal class Record(
        val requestId: String,
        val packageName: String,
        val userId: Int,
        val deadlineNanos: Long,
        val finished: CountDownLatch = CountDownLatch(1),
        var dispatchState: Int = Protocol.DISPATCH_NOT_STARTED,
        var callbackState: Int = Protocol.CALLBACK_NONE,
        var callbackRejected: Boolean = false,
        var abortBeforeDispatch: Int = Protocol.REASON_NONE,
        var observationInterrupted: Boolean = false,
        var workerEntered: Boolean = false,
        var workerExited: Boolean = false,
        var terminal: RootDataClearSnapshot? = null,
    )
}
