// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.os.DeadObjectException
import android.os.RemoteException
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.RootDataClearProtocol as P
import com.valhalla.thor.rootservice.RootDataClearResult
import kotlinx.coroutines.CancellationException

internal sealed interface RootDataClearObservation {
    data object Cleared : RootDataClearObservation
    /** Terminal failure may follow partial changes. It does not establish rollback. */
    data object Failed : RootDataClearObservation
    data class Refused(val reason: Int) : RootDataClearObservation
    data class Unknown(val failure: RootDataClearObservationFailure) : RootDataClearObservation
}

internal enum class RootDataClearObservationFailure {
    INVALID_REQUEST, SERVICE_UNKNOWN, UNSUPPORTED_PROTOCOL, MALFORMED_REPLY,
    TRANSPORT_DIED, TRANSPORT_FAILURE,
}

/** One request or one read-only query. A broken/old reply never triggers another mutation. */
internal class RootDataClearClient(private val service: IThorRootService) {
    fun submit(record: RootDataClearRecord): RootDataClearObservation = call(record) {
        service.clearAppDataForUserWithResult(record.id, record.packageName, record.userId)
    }

    fun query(record: RootDataClearRecord): RootDataClearObservation = call(record) {
        service.getClearAppDataResult(record.id, record.packageName, record.userId)
    }

    private fun call(record: RootDataClearRecord, action: () -> RootDataClearResult?): RootDataClearObservation {
        if (!P.isValidRequestId(record.id) || !P.isValidPackageName(record.packageName) || record.userId < 0) {
            return unknown(RootDataClearObservationFailure.INVALID_REQUEST)
        }
        val reply = try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DeadObjectException) {
            return unknown(RootDataClearObservationFailure.TRANSPORT_DIED)
        } catch (_: RemoteException) {
            return unknown(RootDataClearObservationFailure.TRANSPORT_FAILURE)
        } catch (_: Exception) {
            // Even SecurityException alone does not establish where a remote method threw.
            return unknown(RootDataClearObservationFailure.MALFORMED_REPLY)
        } ?: return unknown(RootDataClearObservationFailure.UNSUPPORTED_PROTOCOL)
        return validate(reply, record)
    }

    private fun validate(reply: RootDataClearResult, record: RootDataClearRecord): RootDataClearObservation {
        fun malformed() = unknown(RootDataClearObservationFailure.MALFORMED_REPLY)
        if (reply.protocolVersion != P.VERSION) return unknown(RootDataClearObservationFailure.UNSUPPORTED_PROTOCOL)
        if (reply.requestId != record.id || reply.packageName != record.packageName || reply.userId != record.userId ||
            !P.isValidRequestId(reply.daemonInstanceId ?: "") || !P.isKnownReason(reply.reason) ||
            reply.dispatchState !in P.DISPATCH_UNKNOWN..P.DISPATCH_UNCERTAIN ||
            reply.callbackState !in P.CALLBACK_NONE..P.CALLBACK_FAILED
        ) return malformed()
        return when (reply.status) {
            P.STATUS_CLEARED -> if (reply.dispatchState == P.DISPATCH_ACCEPTED &&
                reply.callbackState == P.CALLBACK_SUCCEEDED && reply.reason == P.REASON_NONE
            ) RootDataClearObservation.Cleared else malformed()
            P.STATUS_FAILED -> if (reply.dispatchState == P.DISPATCH_ACCEPTED &&
                reply.callbackState == P.CALLBACK_FAILED && reply.reason == P.REASON_CALLBACK_FAILED
            ) RootDataClearObservation.Failed else malformed()
            P.STATUS_REFUSED -> {
                val beforeDispatch = reply.dispatchState == P.DISPATCH_NOT_STARTED &&
                    reply.callbackState == P.CALLBACK_NONE && reply.reason in PRE_DISPATCH_REASONS
                val rejected = reply.dispatchState == P.DISPATCH_REJECTED &&
                    reply.callbackState in setOf(P.CALLBACK_NONE, P.CALLBACK_FAILED) &&
                    reply.reason == P.REASON_DISPATCH_REJECTED
                if (beforeDispatch || rejected) RootDataClearObservation.Refused(reply.reason) else malformed()
            }
            P.STATUS_UNKNOWN -> unknown(RootDataClearObservationFailure.SERVICE_UNKNOWN)
            else -> malformed()
        }
    }

    private fun unknown(failure: RootDataClearObservationFailure) = RootDataClearObservation.Unknown(failure)

    private companion object {
        val PRE_DISPATCH_REASONS = setOf(
            P.REASON_INVALID_ARGUMENT, P.REASON_USER_MISMATCH, P.REASON_BUSY, P.REASON_CAPACITY,
            P.REASON_PREPARATION_FAILED, P.REASON_DEADLINE_BEFORE_DISPATCH,
            P.REASON_WORKER_START_FAILED, P.REASON_SELF_TARGET,
            P.REASON_WAIT_INTERRUPTED,
        )
        // An ID conflict cannot prove that the earlier identity did not dispatch.
    }
}
