// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.valhalla.thor.rootservice.RootDataClearProtocol as Protocol

class RootDataClearLedgerTest {
    @Test
    fun `accepted callback success and failure are distinct terminal observations`() {
        for (succeeded in listOf(true, false)) {
            val fixture = Fixture { callback ->
                PreparedRootDataClear {
                    callback.onCompleted(PACKAGE, succeeded, SYSTEM_UID)
                    true
                }
            }
            val result = fixture.clear()

            assertEquals(if (succeeded) Protocol.STATUS_CLEARED else Protocol.STATUS_FAILED, result.status)
            assertEquals(Protocol.DISPATCH_ACCEPTED, result.dispatchState)
            assertEquals(if (succeeded) Protocol.CALLBACK_SUCCEEDED else Protocol.CALLBACK_FAILED, result.callbackState)
            assertEquals(if (succeeded) Protocol.REASON_NONE else Protocol.REASON_CALLBACK_FAILED, result.reason)
            assertEquals(result, fixture.query())
            assertNotEquals(Protocol.REASON_BUSY, fixture.clear(SECOND_ID).reason)
        }
    }

    @Test
    fun `false dispatch proves nonacceptance and releases admission without a callback`() {
        val fixture = Fixture { PreparedRootDataClear { false } }

        val result = fixture.clear()

        assertEquals(Protocol.STATUS_REFUSED, result.status)
        assertEquals(Protocol.DISPATCH_REJECTED, result.dispatchState)
        assertEquals(Protocol.CALLBACK_NONE, result.callbackState)
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, result.reason)
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear(SECOND_ID).reason)
        assertEquals(2, fixture.preparations.get())
    }

    @Test
    fun `preparation failure proves not started and permits the next request`() {
        val fixture = Fixture { throw SecurityException("Reflection unavailable") }

        val result = fixture.clear()

        assertRefused(result, Protocol.REASON_PREPARATION_FAILED)
        assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_PREPARATION_FAILED)
        assertEquals(2, fixture.preparations.get())
    }

    @Test
    fun `accepted missing callback retains admission until a valid late callback`() {
        lateinit var callback: RootDataClearCallback
        val fixture = Fixture { observer ->
            callback = observer
            PreparedRootDataClear { true }
        }

        val pending = fixture.clear()

        assertEquals(Protocol.STATUS_UNKNOWN, pending.status)
        assertEquals(Protocol.DISPATCH_ACCEPTED, pending.dispatchState)
        assertEquals(Protocol.REASON_WAIT_EXPIRED, pending.reason)
        assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_BUSY)
        callback.onCompleted(PACKAGE, true, SYSTEM_UID)
        assertEquals(Protocol.STATUS_CLEARED, fixture.query().status)
        assertEquals(Protocol.STATUS_UNKNOWN, pending.status)
        assertEquals(Protocol.STATUS_UNKNOWN, fixture.clear(SECOND_ID).status)
        assertEquals(2, fixture.preparations.get())
    }

    @Test
    fun `callback during dispatch cannot release admission before worker exits`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val fixture = Fixture { callback ->
            PreparedRootDataClear {
                callback.onCompleted(PACKAGE, true, SYSTEM_UID)
                entered.countDown()
                await(release)
                true
            }
        }
        fixture.worker = { work ->
            daemon {
                try { work.run() } finally { exited.countDown() }
            }
            await(entered)
            fixture.expireObservation()
        }

        try {
            val pending = fixture.clear()
            assertEquals(Protocol.STATUS_UNKNOWN, pending.status)
            assertEquals(Protocol.DISPATCH_IN_FLIGHT, pending.dispatchState)
            assertEquals(Protocol.CALLBACK_SUCCEEDED, pending.callbackState)
            assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_BUSY)
        } finally {
            release.countDown()
            await(exited)
        }

        assertEquals(Protocol.STATUS_CLEARED, fixture.query().status)
        fixture.worker = fixture.inlineWorker
        assertEquals(Protocol.STATUS_CLEARED, fixture.clear(SECOND_ID).status)
    }

    @Test
    fun `invocation exception remains uncertain even with earlier or later callback evidence`() {
        for (callbackBeforeThrow in listOf(false, true)) {
            lateinit var callback: RootDataClearCallback
            val fixture = Fixture { observer ->
                callback = observer
                PreparedRootDataClear {
                    if (callbackBeforeThrow) observer.onCompleted(PACKAGE, true, SYSTEM_UID)
                    throw SecurityException("Exception after invocation boundary")
                }
            }
            val result = fixture.clear()
            assertEquals(Protocol.STATUS_UNKNOWN, result.status)
            assertEquals(Protocol.DISPATCH_UNCERTAIN, result.dispatchState)
            assertEquals(Protocol.REASON_DISPATCH_EXCEPTION, result.reason)

            callback.onCompleted(PACKAGE, true, SYSTEM_UID)
            val late = fixture.query()
            assertEquals(Protocol.STATUS_UNKNOWN, late.status)
            assertEquals(Protocol.CALLBACK_SUCCEEDED, late.callbackState)
            assertEquals(Protocol.REASON_DISPATCH_EXCEPTION, late.reason)
            assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_BUSY)
            assertEquals(1, fixture.preparations.get())
        }
    }

    @Test
    fun `successful callback contradicting false dispatch retains admission`() {
        val fixture = Fixture { callback ->
            PreparedRootDataClear {
                callback.onCompleted(PACKAGE, true, SYSTEM_UID)
                false
            }
        }

        val result = fixture.clear()

        assertEquals(Protocol.STATUS_UNKNOWN, result.status)
        assertEquals(Protocol.DISPATCH_REJECTED, result.dispatchState)
        assertEquals(Protocol.CALLBACK_SUCCEEDED, result.callbackState)
        assertEquals(Protocol.REASON_STATE_CONTRADICTION, result.reason)
        assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_BUSY)
    }

    @Test
    fun `untrusted mismatched null and predispatch callbacks cannot consume the first valid verdict`() {
        lateinit var callback: RootDataClearCallback
        val fixture = Fixture { observer ->
            callback = observer
            observer.onCompleted(PACKAGE, true, SYSTEM_UID)
            PreparedRootDataClear { true }
        }
        fixture.clear()
        callback.onCompleted(PACKAGE, true, CALLER_UID)
        callback.onCompleted(OTHER_PACKAGE, true, SYSTEM_UID)
        callback.onCompleted(null, true, 0)

        val rejected = fixture.query()
        assertEquals(Protocol.STATUS_UNKNOWN, rejected.status)
        assertEquals(Protocol.CALLBACK_NONE, rejected.callbackState)
        assertEquals(Protocol.REASON_CALLBACK_REJECTED, rejected.reason)
        assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_BUSY)

        callback.onCompleted(PACKAGE, false, 0)
        callback.onCompleted(PACKAGE, true, SYSTEM_UID)
        assertEquals(Protocol.STATUS_FAILED, fixture.query().status)
        assertEquals(Protocol.CALLBACK_FAILED, fixture.query().callbackState)
    }

    @Test
    fun `first valid callback wins while the worker is still invoking`() {
        val fixture = Fixture { callback ->
            PreparedRootDataClear {
                callback.onCompleted(PACKAGE, true, SYSTEM_UID)
                callback.onCompleted(PACKAGE, false, 0)
                true
            }
        }

        assertEquals(Protocol.STATUS_CLEARED, fixture.clear().status)
    }

    @Test
    fun `same retained request never redispatches and conflicting package or user is refused`() {
        val fixture = Fixture { PreparedRootDataClear { false } }
        val original = fixture.clear(callerUid = 0)

        assertEquals(original, fixture.clear(callerUid = 0))
        assertEquals(original, fixture.query(callerUid = 0))
        assertRefused(fixture.clear(packageName = OTHER_PACKAGE, callerUid = 0), Protocol.REASON_REQUEST_ID_CONFLICT)
        assertRefused(fixture.clear(userId = USER + 1, callerUid = 0), Protocol.REASON_REQUEST_ID_CONFLICT)
        assertRefused(fixture.query(packageName = OTHER_PACKAGE, callerUid = 0), Protocol.REASON_REQUEST_ID_CONFLICT)
        assertEquals(1, fixture.preparations.get())
    }

    @Test
    fun `duplicate pending request and query never start a second worker`() {
        val fixture = Fixture { PreparedRootDataClear { true } }
        val original = fixture.clear()

        repeat(3) {
            assertEquals(original, fixture.clear())
            assertEquals(original, fixture.query())
        }
        assertEquals(1, fixture.preparations.get())
    }

    @Test
    fun `records are scoped to exact caller uid and ordinary callers cannot target other users`() {
        val fixture = Fixture { PreparedRootDataClear { false } }
        fixture.clear()

        assertEquals(Protocol.REASON_NOT_FOUND, fixture.query(callerUid = CALLER_UID + 1).reason)
        assertRefused(fixture.clear(userId = USER + 1), Protocol.REASON_USER_MISMATCH)
        assertRefused(fixture.query(userId = USER + 1), Protocol.REASON_USER_MISMATCH)
        assertEquals(1, fixture.preparations.get())
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear(callerUid = CALLER_UID + 1).reason)
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear(userId = USER + 1, callerUid = 0).reason)
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear(userId = USER + 1, callerUid = SYSTEM_UID).reason)
        assertEquals(4, fixture.preparations.get())
    }

    @Test
    fun `bounded history keeps old terminal results and refuses overflow before preparation`() {
        val fixture = Fixture(maxRecords = 1) { PreparedRootDataClear { false } }
        val original = fixture.clear()

        assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_CAPACITY)
        assertEquals(original, fixture.query())
        assertEquals(original, fixture.clear())
        assertEquals(1, fixture.preparations.get())
    }

    @Test
    fun `service package is protected before preparation for every user`() {
        val fixture = Fixture { PreparedRootDataClear { false } }

        assertRefused(fixture.clear(packageName = SELF_PACKAGE), Protocol.REASON_SELF_TARGET)
        assertRefused(fixture.clear(packageName = SELF_PACKAGE, userId = USER + 1, callerUid = 0), Protocol.REASON_SELF_TARGET)
        assertEquals(0, fixture.preparations.get())
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear().reason)
    }

    @Test
    fun `queued worker observes the deadline and never begins preparation`() {
        lateinit var queued: Runnable
        val fixture = Fixture { PreparedRootDataClear { false } }
        fixture.worker = { work ->
            queued = work
            fixture.expireObservation()
        }

        val pending = fixture.clear()
        assertEquals(Protocol.STATUS_UNKNOWN, pending.status)
        assertEquals(Protocol.DISPATCH_NOT_STARTED, pending.dispatchState)
        assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_BUSY)
        queued.run()

        assertRefused(fixture.query(), Protocol.REASON_DEADLINE_BEFORE_DISPATCH)
        assertEquals(0, fixture.preparations.get())
        fixture.worker = fixture.inlineWorker
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear(SECOND_ID).reason)
    }

    @Test
    fun `deadline reached while preparing prevents invocation`() {
        val dispatches = AtomicInteger()
        lateinit var fixture: Fixture
        fixture = Fixture {
            fixture.expireObservation()
            PreparedRootDataClear { dispatches.incrementAndGet(); true }
        }

        assertRefused(fixture.clear(), Protocol.REASON_DEADLINE_BEFORE_DISPATCH)
        assertEquals(1, fixture.preparations.get())
        assertEquals(0, dispatches.get())
    }

    @Test
    fun `caller interruption before dispatch aborts later invocation and preserves interrupt status`() {
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val dispatches = AtomicInteger()
        val returned = AtomicReference<RootDataClearSnapshot>()
        val interruptPreserved = AtomicReference(false)
        val fixture = Fixture(timeoutMillis = 5_000L) {
            preparing.countDown()
            await(release)
            PreparedRootDataClear { dispatches.incrementAndGet(); true }
        }
        fixture.worker = { work -> daemon { try { work.run() } finally { exited.countDown() } } }
        val caller = daemon {
            returned.set(fixture.clear())
            interruptPreserved.set(Thread.currentThread().isInterrupted)
        }

        try {
            await(preparing)
            caller.interrupt()
            caller.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("Interrupted caller must return", caller.isAlive)
            assertTrue(interruptPreserved.get())
            assertEquals(Protocol.STATUS_UNKNOWN, returned.get().status)
            assertEquals(Protocol.REASON_WAIT_INTERRUPTED, returned.get().reason)
        } finally {
            release.countDown()
            await(exited)
        }

        assertRefused(fixture.query(), Protocol.REASON_WAIT_INTERRUPTED)
        assertEquals(0, dispatches.get())
    }

    @Test
    fun `caller interruption after invocation cannot cancel work or release admission`() {
        val invoking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val returned = AtomicReference<RootDataClearSnapshot>()
        val workerWasInterrupted = AtomicReference(false)
        val fixture = Fixture(timeoutMillis = 5_000L) { callback ->
            PreparedRootDataClear {
                invoking.countDown()
                await(release)
                workerWasInterrupted.set(Thread.currentThread().isInterrupted)
                callback.onCompleted(PACKAGE, true, SYSTEM_UID)
                true
            }
        }
        fixture.worker = { work -> daemon { try { work.run() } finally { exited.countDown() } } }
        val caller = daemon { returned.set(fixture.clear()) }

        try {
            await(invoking)
            caller.interrupt()
            caller.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("Interrupted caller must return", caller.isAlive)
            assertEquals(Protocol.STATUS_UNKNOWN, returned.get().status)
            assertEquals(Protocol.DISPATCH_IN_FLIGHT, returned.get().dispatchState)
            assertEquals(Protocol.REASON_WAIT_INTERRUPTED, returned.get().reason)
            assertRefused(fixture.clear(SECOND_ID), Protocol.REASON_BUSY)
        } finally {
            release.countDown()
            await(exited)
        }

        assertFalse(workerWasInterrupted.get())
        assertEquals(Protocol.STATUS_CLEARED, fixture.query().status)
        fixture.worker = fixture.inlineWorker
        assertEquals(Protocol.STATUS_CLEARED, fixture.clear(SECOND_ID).status)
    }

    @Test
    fun `missing record in the original or replacement daemon never claims not started`() {
        val first = Fixture { PreparedRootDataClear { true } }
        first.clear()
        val replacement = Fixture(daemonId = OTHER_DAEMON_ID) { PreparedRootDataClear { false } }

        for (result in listOf(first.query(SECOND_ID), replacement.query())) {
            assertEquals(Protocol.STATUS_UNKNOWN, result.status)
            assertEquals(Protocol.DISPATCH_UNKNOWN, result.dispatchState)
            assertEquals(Protocol.REASON_NOT_FOUND, result.reason)
        }
        assertNotEquals(first.query().daemonInstanceId, replacement.query().daemonInstanceId)
        assertEquals(0, replacement.preparations.get())
    }

    @Test
    fun `parcelable replies are fresh copies and caller mutation cannot alter history`() {
        val fixture = Fixture { PreparedRootDataClear { false } }
        val original = fixture.clear()
        val first = original.toParcelable()
        val second = original.toParcelable()
        assertNotSame(first, second)

        first.requestId = SECOND_ID
        first.daemonInstanceId = OTHER_DAEMON_ID
        first.packageName = OTHER_PACKAGE
        first.userId = USER + 1
        first.status = Protocol.STATUS_CLEARED
        first.dispatchState = Protocol.DISPATCH_ACCEPTED
        first.callbackState = Protocol.CALLBACK_SUCCEEDED
        first.reason = Protocol.REASON_NONE
        first.protocolVersion = -1

        assertEquals(original, fixture.query())
        assertEquals(ID, second.requestId)
        assertEquals(DAEMON_ID, second.daemonInstanceId)
        assertEquals(PACKAGE, second.packageName)
        assertEquals(USER, second.userId)
        assertEquals(Protocol.STATUS_REFUSED, second.status)
        assertEquals(Protocol.DISPATCH_REJECTED, second.dispatchState)
        assertEquals(Protocol.CALLBACK_NONE, second.callbackState)
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, second.reason)
        assertEquals(Protocol.VERSION, second.protocolVersion)
    }

    @Test
    fun `worker starter failure cannot leave queued destructive work or strand admission`() {
        lateinit var queued: Runnable
        val fixture = Fixture { PreparedRootDataClear { false } }
        fixture.worker = { work ->
            queued = work
            throw IllegalStateException("Queued but could not start")
        }

        assertRefused(fixture.clear(), Protocol.REASON_WORKER_START_FAILED)
        fixture.worker = fixture.inlineWorker
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear(SECOND_ID).reason)
        queued.run()
        assertRefused(fixture.query(), Protocol.REASON_WORKER_START_FAILED)
        assertEquals(1, fixture.preparations.get())
    }

    @Test
    fun `malformed and oversized request identities are refused before preparation`() {
        val fixture = Fixture { PreparedRootDataClear { false } }
        for (requestId in listOf(null, "", ID.uppercase(), "x".repeat(37), "not-a-uuid")) {
            assertRefused(fixture.clear(requestId), Protocol.REASON_INVALID_ARGUMENT)
            assertRefused(fixture.query(requestId), Protocol.REASON_INVALID_ARGUMENT)
        }
        for (packageName in listOf(null, "", "com.example;pm clear", "a".repeat(256), "com/example")) {
            assertRefused(fixture.clear(packageName = packageName), Protocol.REASON_INVALID_ARGUMENT)
            assertRefused(fixture.query(packageName = packageName), Protocol.REASON_INVALID_ARGUMENT)
        }
        assertRefused(fixture.clear(userId = -1), Protocol.REASON_INVALID_ARGUMENT)
        assertRefused(fixture.clear(callerUid = -1), Protocol.REASON_INVALID_ARGUMENT)
        assertEquals(0, fixture.preparations.get())
        assertTrue(Protocol.isValidPackageName("a".repeat(255)))
        assertTrue(Protocol.isValidPackageName("android"))
        assertEquals(Protocol.REASON_DISPATCH_REJECTED, fixture.clear().reason)
    }

    private class Fixture(
        maxRecords: Int = Protocol.MAX_RECORDS,
        daemonId: String = DAEMON_ID,
        private val timeoutMillis: Long = TIMEOUT_MILLIS,
        prepare: (RootDataClearCallback) -> PreparedRootDataClear,
    ) {
        val preparations = AtomicInteger()
        private val clock = AtomicLong()
        val inlineWorker: (Runnable) -> Unit = { work ->
            work.run()
            expireObservation()
        }
        var worker: (Runnable) -> Unit = inlineWorker
        private val ledger = RootDataClearLedger(
            preparation = RootDataClearPreparation { _, _, callback ->
                preparations.incrementAndGet()
                prepare(callback)
            },
            protectedPackageName = SELF_PACKAGE,
            observationTimeoutMillis = timeoutMillis,
            maxRecords = maxRecords,
            nowNanos = clock::get,
            startWorker = { worker(it) },
            daemonInstanceId = daemonId,
        )

        fun expireObservation() { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) }

        fun clear(
            requestId: String? = ID,
            packageName: String? = PACKAGE,
            userId: Int = USER,
            callerUid: Int = CALLER_UID,
        ) = ledger.clear(requestId, packageName, userId, callerUid)

        fun query(
            requestId: String? = ID,
            packageName: String? = PACKAGE,
            userId: Int = USER,
            callerUid: Int = CALLER_UID,
        ) = ledger.query(requestId, packageName, userId, callerUid)
    }

    private companion object {
        const val ID = "8fd272a4-b545-489a-a8b1-7174566f02c4"
        const val SECOND_ID = "2cbeee2a-a973-42d0-912f-d6c45b193743"
        const val DAEMON_ID = "6eec9e4e-17b2-4f07-b9a7-1ca54aa9e099"
        const val OTHER_DAEMON_ID = "b7a933c9-46b5-405e-bff1-bf65ea73ae16"
        const val PACKAGE = "com.example.target"
        const val OTHER_PACKAGE = "com.example.other"
        const val SELF_PACKAGE = "com.valhalla.thor"
        const val USER = 10
        const val CALLER_UID = 1_010_001
        const val SYSTEM_UID = 1_000
        const val TIMEOUT_MILLIS = 100L

        fun await(latch: CountDownLatch) {
            check(latch.await(5, TimeUnit.SECONDS)) { "Worker ordering latch timed out" }
        }

        fun daemon(block: () -> Unit) = Thread(block, "root-data-clear-ledger-test")
            .apply { isDaemon = true; start() }

        fun assertRefused(result: RootDataClearSnapshot, reason: Int) {
            assertEquals(Protocol.STATUS_REFUSED, result.status)
            assertEquals(Protocol.DISPATCH_NOT_STARTED, result.dispatchState)
            assertEquals(reason, result.reason)
        }
    }
}
