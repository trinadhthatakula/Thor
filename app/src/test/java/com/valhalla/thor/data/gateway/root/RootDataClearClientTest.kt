// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.app.Application
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.RootDataClearProtocol as P
import com.valhalla.thor.rootservice.RootDataClearResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootDataClearClientTest {
    @Test
    fun `submit and query each dispatch once and accept only their matching terminal observation`() {
        val service = RecordingService { reply() }
        val client = RootDataClearClient(service)

        assertEquals(RootDataClearObservation.Cleared, client.submit(RECORD))
        assertEquals(RootDataClearObservation.Cleared, client.query(RECORD))

        assertEquals(listOf(Call("submit", RECORD.id, PACKAGE, USER), Call("query", RECORD.id, PACKAGE, USER)), service.calls)
        assertEquals(0, service.legacyCalls)
        assertEquals(
            RootDataClearObservation.Failed,
            read(reply(P.STATUS_FAILED, P.DISPATCH_ACCEPTED, P.CALLBACK_FAILED, P.REASON_CALLBACK_FAILED)),
        )
    }

    @Test
    fun `pre-dispatch refusal and rejected dispatch require exact non-executing evidence`() {
        val beforeDispatchReasons = listOf(
            P.REASON_INVALID_ARGUMENT, P.REASON_USER_MISMATCH, P.REASON_BUSY, P.REASON_CAPACITY,
            P.REASON_PREPARATION_FAILED, P.REASON_DEADLINE_BEFORE_DISPATCH,
            P.REASON_WORKER_START_FAILED, P.REASON_SELF_TARGET, P.REASON_WAIT_INTERRUPTED,
        )
        for (reason in beforeDispatchReasons) {
            assertEquals(
                RootDataClearObservation.Refused(reason),
                read(reply(P.STATUS_REFUSED, P.DISPATCH_NOT_STARTED, P.CALLBACK_NONE, reason)),
            )
        }
        for (callback in listOf(P.CALLBACK_NONE, P.CALLBACK_FAILED)) {
            assertEquals(
                RootDataClearObservation.Refused(P.REASON_DISPATCH_REJECTED),
                read(reply(P.STATUS_REFUSED, P.DISPATCH_REJECTED, callback, P.REASON_DISPATCH_REJECTED)),
            )
        }
    }

    @Test
    fun `wrong request package user daemon status and numeric values remain nonactionable`() {
        val invalid = listOf(
            reply().apply { requestId = OTHER_ID },
            reply().apply { packageName = "com.example.other" },
            reply().apply { userId = USER + 1 },
            reply().apply { daemonInstanceId = "invalid-daemon" },
            reply().apply { javaClass.getField("daemonInstanceId").set(this, null) },
            reply().apply { status = 99 },
            reply().apply { reason = 99 },
            reply().apply { dispatchState = -1 },
            reply().apply { dispatchState = P.DISPATCH_UNCERTAIN + 1 },
            reply().apply { callbackState = -1 },
            reply().apply { callbackState = P.CALLBACK_FAILED + 1 },
        )
        invalid.forEach { assertEquals(malformed(), read(it)) }
    }

    @Test
    fun `contradictory terminal status dispatch callback and reason tuples cannot retire ownership`() {
        val invalid = listOf(
            reply(dispatch = P.DISPATCH_UNKNOWN),
            reply(dispatch = P.DISPATCH_IN_FLIGHT),
            reply(dispatch = P.DISPATCH_UNCERTAIN),
            reply(callback = P.CALLBACK_NONE),
            reply(callback = P.CALLBACK_FAILED),
            reply(reason = P.REASON_WAIT_EXPIRED),
            reply(P.STATUS_FAILED, P.DISPATCH_ACCEPTED, P.CALLBACK_SUCCEEDED, P.REASON_CALLBACK_FAILED),
            reply(P.STATUS_FAILED, P.DISPATCH_NOT_STARTED, P.CALLBACK_FAILED, P.REASON_CALLBACK_FAILED),
            reply(P.STATUS_FAILED, P.DISPATCH_ACCEPTED, P.CALLBACK_FAILED, P.REASON_NONE),
            reply(P.STATUS_REFUSED, P.DISPATCH_ACCEPTED, P.CALLBACK_NONE, P.REASON_BUSY),
            reply(P.STATUS_REFUSED, P.DISPATCH_NOT_STARTED, P.CALLBACK_SUCCEEDED, P.REASON_BUSY),
            reply(P.STATUS_REFUSED, P.DISPATCH_NOT_STARTED, P.CALLBACK_NONE, P.REASON_NONE),
            reply(P.STATUS_REFUSED, P.DISPATCH_NOT_STARTED, P.CALLBACK_NONE, P.REASON_REQUEST_ID_CONFLICT),
            reply(P.STATUS_REFUSED, P.DISPATCH_NOT_STARTED, P.CALLBACK_NONE, P.REASON_NOT_FOUND),
            reply(P.STATUS_REFUSED, P.DISPATCH_REJECTED, P.CALLBACK_SUCCEEDED, P.REASON_DISPATCH_REJECTED),
            reply(P.STATUS_REFUSED, P.DISPATCH_REJECTED, P.CALLBACK_NONE, P.REASON_CALLBACK_FAILED),
        )
        invalid.forEach {
            assertEquals("status=${it.status}, dispatch=${it.dispatchState}, callback=${it.callbackState}, reason=${it.reason}",
                malformed(), read(it))
        }
    }

    @Test
    fun `unknown observations including late callback without accepted dispatch stay unknown`() {
        val unknownReplies = listOf(
            reply(P.STATUS_UNKNOWN, P.DISPATCH_UNKNOWN, P.CALLBACK_NONE, P.REASON_NOT_FOUND),
            reply(P.STATUS_UNKNOWN, P.DISPATCH_ACCEPTED, P.CALLBACK_NONE, P.REASON_WAIT_EXPIRED),
            reply(P.STATUS_UNKNOWN, P.DISPATCH_IN_FLIGHT, P.CALLBACK_NONE, P.REASON_IN_PROGRESS),
            reply(P.STATUS_UNKNOWN, P.DISPATCH_UNCERTAIN, P.CALLBACK_SUCCEEDED, P.REASON_DISPATCH_EXCEPTION),
            reply(P.STATUS_UNKNOWN, P.DISPATCH_REJECTED, P.CALLBACK_SUCCEEDED, P.REASON_STATE_CONTRADICTION),
        )
        unknownReplies.forEach {
            assertEquals(RootDataClearObservation.Unknown(RootDataClearObservationFailure.SERVICE_UNKNOWN), read(it))
        }
    }

    @Test
    fun `null and unsupported versions neither resubmit nor fall back to legacy mutations`() {
        for (response in listOf(null, reply().apply { protocolVersion = P.VERSION + 1 })) {
            val service = RecordingService { response }
            val result = RootDataClearClient(service).submit(RECORD)

            assertEquals(RootDataClearObservation.Unknown(RootDataClearObservationFailure.UNSUPPORTED_PROTOCOL), result)
            assertEquals(listOf(Call("submit", RECORD.id, PACKAGE, USER)), service.calls)
            assertEquals(0, service.legacyCalls)
        }
    }

    @Test
    fun `dead transport remote failure and thrown rejection cannot become service-confirmed refusal`() {
        val failures = listOf(
            DeadObjectException() to RootDataClearObservationFailure.TRANSPORT_DIED,
            RemoteException("reply lost") to RootDataClearObservationFailure.TRANSPORT_FAILURE,
            SecurityException("caller rejection") to RootDataClearObservationFailure.MALFORMED_REPLY,
            IllegalArgumentException("malformed parcel") to RootDataClearObservationFailure.MALFORMED_REPLY,
        )
        for ((failure, expected) in failures) {
            val service = RecordingService { throw failure }
            assertEquals(RootDataClearObservation.Unknown(expected), RootDataClearClient(service).submit(RECORD))
            assertEquals(1, service.calls.size)
            assertEquals(0, service.legacyCalls)
        }
    }

    @Test
    fun `invalid request never dispatches and cancellation propagates without follow-up`() {
        val cancellation = CancellationException("caller cancelled")
        val service = RecordingService { throw cancellation }
        val client = RootDataClearClient(service)
        val invalid = listOf(RECORD.copy(id = ""), RECORD.copy(packageName = "--all"), RECORD.copy(userId = -1))
        for (record in invalid) {
            assertEquals(RootDataClearObservation.Unknown(RootDataClearObservationFailure.INVALID_REQUEST), client.submit(record))
            assertEquals(RootDataClearObservation.Unknown(RootDataClearObservationFailure.INVALID_REQUEST), client.query(record))
        }
        assertTrue(service.calls.isEmpty())
        for (call in listOf(client::submit, client::query)) {
            val caught = try {
                call(RECORD)
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }
            assertSame(cancellation, caught)
        }
        assertEquals(listOf("submit", "query"), service.calls.map { it.method })
        assertEquals(0, service.legacyCalls)
    }

    @Test
    fun `proxy preserves seven old slots and old service rejects each new call without fallback`() {
        val transactions = mutableListOf<Int>()
        val legacy = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                transactions += code
                data.enforceInterface(DESCRIPTOR)
                if (code >= IBinder.FIRST_CALL_TRANSACTION + 7) return false
                val response = requireNotNull(reply)
                response.writeNoException()
                if (code == IBinder.FIRST_CALL_TRANSACTION + 3) response.writeString(null)
                else response.writeInt(0)
                return true
            }
        }
        val service = IThorRootService.Stub.asInterface(legacy)
        service.setAppSuspended(PACKAGE, false)
        service.clearAppData(PACKAGE)
        service.setAppSuspendedAs(PACKAGE, false, null)
        service.dumpPackage(PACKAGE)
        service.clearAppDataForUser(PACKAGE, USER)
        service.setAppSuspendedAsForUser(PACKAGE, false, null, USER)
        service.getSuspensionStateForUser(PACKAGE, USER)
        assertEquals((0..6).map { IBinder.FIRST_CALL_TRANSACTION + it }, transactions)

        val client = RootDataClearClient(service)
        transactions.clear()
        assertTrue(client.submit(RECORD) is RootDataClearObservation.Unknown)
        assertEquals(listOf(IBinder.FIRST_CALL_TRANSACTION + 7), transactions)
        transactions.clear()
        assertTrue(client.query(RECORD) is RootDataClearObservation.Unknown)
        assertEquals(listOf(IBinder.FIRST_CALL_TRANSACTION + 8), transactions)
    }

    private fun read(response: RootDataClearResult): RootDataClearObservation =
        RootDataClearClient(RecordingService { response }).query(RECORD)

    private fun malformed() = RootDataClearObservation.Unknown(RootDataClearObservationFailure.MALFORMED_REPLY)

    private data class Call(val method: String, val id: String, val packageName: String, val userId: Int)

    private class RecordingService(private val response: () -> RootDataClearResult?) : IThorRootService.Default() {
        val calls = mutableListOf<Call>()
        var legacyCalls = 0
        override fun clearAppDataForUserWithResult(requestId: String, packageName: String, userId: Int): RootDataClearResult? {
            calls += Call("submit", requestId, packageName, userId)
            return response()
        }
        override fun getClearAppDataResult(requestId: String, packageName: String, userId: Int): RootDataClearResult? {
            calls += Call("query", requestId, packageName, userId)
            return response()
        }
        override fun clearAppData(packageName: String): Boolean {
            legacyCalls++
            error("No legacy mutation may be replayed")
        }
        override fun clearAppDataForUser(packageName: String, userId: Int): Boolean {
            legacyCalls++
            error("No legacy mutation may be replayed")
        }
    }

    private companion object {
        const val PACKAGE = "com.example.target"
        const val USER = 10
        const val ID = "0e51f68c-1e2b-4cb6-a91b-1c086121446a"
        const val OTHER_ID = "22b94c38-6738-46b6-b225-f6584852c995"
        const val DAEMON = "2cc30973-11e7-452b-9d7a-1b688e8eab3a"
        const val DESCRIPTOR = "com.valhalla.thor.rootservice.IThorRootService"
        val RECORD = RootDataClearRecord(ID, PACKAGE, USER, null, RootDataClearPhase.BINDER)

        fun reply(
            status: Int = P.STATUS_CLEARED,
            dispatch: Int = P.DISPATCH_ACCEPTED,
            callback: Int = P.CALLBACK_SUCCEEDED,
            reason: Int = P.REASON_NONE,
        ) = RootDataClearResult().apply {
            protocolVersion = P.VERSION
            requestId = ID
            daemonInstanceId = DAEMON
            packageName = PACKAGE
            userId = USER
            this.status = status
            dispatchState = dispatch
            callbackState = callback
            this.reason = reason
        }
    }
}
