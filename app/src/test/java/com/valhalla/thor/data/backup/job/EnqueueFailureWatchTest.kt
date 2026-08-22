// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [whenFailed] — the cleanup that survives its caller.
 *
 * The sequence these cover: a caller stops waiting for an enqueue (its coroutine is cancelled), and the
 * enqueue *then* fails. No worker will ever run, so the key put in [ArchiveKeyHolder] before the enqueue
 * has nothing left that could consume it, and the frame that would have dropped it is gone.
 *
 * **What is pinned here and what is not.** These pin the watch's own contract — fails ⇒ release, succeeds
 * ⇒ do not, and the argument is the *cause* rather than the wrapper. They do **not** pin that
 * [enqueueUniqueJob]'s cancellation branch registers the watch: that call needs `WorkManager.getInstance`,
 * and with no `work-testing` or Robolectric on this classpath there is no JVM seam for it. That half is
 * one line, and it is verified by reading it.
 */
class EnqueueFailureWatchTest {

    /**
     * The five `Future` methods plus `addListener`, with the two orderings that matter: completing after a
     * listener is registered, and registering on a future that is already done. Guava's contract is that
     * the listener runs immediately in the second case, and a fake that forgot to do that would make the
     * "already failed" test below pass for the wrong reason.
     */
    private class FakeFuture : ListenableFuture<String> {
        private var listener: Runnable? = null
        private var executor: Executor? = null
        private var failure: Throwable? = null
        private var cancelled = false
        private var done = false

        fun succeed() = complete { done = true }
        fun fail(cause: Throwable) = complete { failure = cause; done = true }
        fun cancelFuture() = complete { cancelled = true; done = true }

        private fun complete(settle: () -> Unit) {
            settle()
            val pending = listener ?: return
            (executor ?: Executor { it.run() }).execute(pending)
        }

        override fun addListener(listener: Runnable, executor: Executor) {
            this.listener = listener
            this.executor = executor
            if (done) executor.execute(listener)
        }

        override fun get(): String = when {
            cancelled -> throw CancellationException("the operation's own future was cancelled")
            failure != null -> throw ExecutionException(failure)
            else -> "enqueued"
        }

        override fun get(timeout: Long, unit: TimeUnit): String = get()
        override fun isDone(): Boolean = done
        override fun isCancelled(): Boolean = cancelled
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    }

    private val future = FakeFuture()
    private val released = mutableListOf<Throwable>()

    @Test
    fun `an enqueue that fails after nobody is waiting still releases what it was holding`() {
        val reason = IllegalStateException("WorkSpec insert failed")
        future.whenFailed { released.add(it) }

        future.fail(reason)

        assertEquals(1, released.size)
        assertSame(
            "the handler gets the cause, not the ExecutionException wrapping it — the wrapper says " +
                "nothing about what went wrong",
            reason,
            released.single(),
        )
    }

    @Test
    fun `an enqueue that succeeds after the wait was cancelled releases nothing`() {
        future.whenFailed { released.add(it) }

        future.succeed()

        assertEquals(
            "the work is with WorkManager and its worker still needs the key; this is the case the " +
                "cancellation branch exists to protect",
            emptyList<Throwable>(),
            released,
        )
    }

    @Test
    fun `a future that had already failed by the time the watch was registered releases immediately`() {
        val reason = IllegalStateException("enqueue was rejected")
        future.fail(reason)

        future.whenFailed { released.add(it) }

        assertSame(reason, released.singleOrNull())
    }

    @Test
    fun `a cancelled future releases nothing, because it does not say the enqueue failed`() {
        future.whenFailed { released.add(it) }

        future.cancelFuture()

        assertEquals(
            "a cancelled future says nothing about whether the WorkSpec landed; releasing a key a live " +
                "worker needs is the worse of the two failures, so this fails closed and lets the " +
                "holder's own expiry deal with it",
            emptyList<Throwable>(),
            released,
        )
    }

    @Test
    fun `a get that throws is swallowed instead of thrown into the thread that completed the future`() {
        future.whenFailed { released.add(it) }

        // Distinct from the test above, which pins what was *released*: this pins that the listener body
        // itself is exception-free. `get()` on a cancelled future throws, and that throw would otherwise
        // travel into WorkManager's executor, which is not expecting Thor's exceptions.
        assertNull(runCatching { future.cancelFuture() }.exceptionOrNull())
    }
}
