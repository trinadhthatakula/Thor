// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationLeaseRetentionTest {
    @Test
    fun `nested leases survive lexical exit until every retained group releases`() = runTest {
        val released = mutableListOf<String>()
        lateinit var first: RetainedOperationLease
        lateinit var second: RetainedOperationLease
        withRetainableOperationLease(release = { released += "outer" }) {
            withRetainableOperationLease(release = { released += "inner" }) {
                first = retainOperationLeases()
                second = retainOperationLeases()
            }
            assertTrue(released.isEmpty())
        }

        first.release()
        first.release()
        assertTrue(released.isEmpty())
        second.release()
        assertEquals(listOf("inner", "outer"), released)
        second.release()
        assertEquals(listOf("inner", "outer"), released)
    }

    @Test
    fun `terminal release before lexical exit preserves the caller ownership`() = runTest {
        var releases = 0
        withRetainableOperationLease(release = { releases++ }) {
            retainOperationLeases().release()
            assertEquals(0, releases)
        }
        assertEquals(1, releases)
    }

    @Test
    fun `cancelled caller can finish lexical and retained cleanup`() = runTest {
        var releases = 0
        val entered = CompletableDeferred<Unit>()
        val caller = async {
            withRetainableOperationLease(release = { releases++ }) {
                val retained = retainOperationLeases()
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    retained.release()
                }
            }
        }
        entered.await()
        caller.cancelAndJoin()
        assertEquals(1, releases)
    }

    @Test
    fun `concurrent duplicate release waits for the original release`() = runTest {
        var releases = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val retained = withRetainableOperationLease(release = {
            releases++
            started.complete(Unit)
            finish.await()
        }) { retainOperationLeases() }

        val first = async { retained.release() }
        started.await()
        val duplicate = async { retained.release() }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(duplicate.isCompleted)
        finish.complete(Unit)
        first.await()
        duplicate.await()
        assertEquals(1, releases)
    }

    @Test
    fun `a stale nested context cannot retain exited ownership or leak an outer reference`() = runTest {
        val released = mutableListOf<String>()
        withRetainableOperationLease(release = { released += "outer" }) {
            lateinit var stale: CoroutineContext
            withRetainableOperationLease(release = { released += "inner" }) {
                stale = currentCoroutineContext().minusKey(Job)
            }
            val failure = try {
                withContext(stale) { retainOperationLeases() }
                null
            } catch (expected: IllegalStateException) {
                expected
            }
            assertTrue(failure is IllegalStateException)
            assertEquals(listOf("inner"), released)
        }
        assertEquals(listOf("inner", "outer"), released)
    }

    @Test
    fun `failure releasing one nested lease still releases the remaining leases`() = runTest {
        val innerFailure = IllegalStateException("inner release failed")
        val outerFailure = IllegalStateException("outer release failed")
        val retained = withRetainableOperationLease(release = { throw outerFailure }) {
            withRetainableOperationLease(release = { throw innerFailure }) {
                retainOperationLeases()
            }
        }
        val failure = try {
            retained.release()
            null
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue(failure is IllegalStateException)
        assertEquals(innerFailure.message, failure?.message)
        // Coroutine stack-trace recovery wraps the thrown exception; suppression is on its cause.
        assertEquals(listOf(outerFailure), innerFailure.suppressed.toList())
    }

    @Test
    fun `an empty lease context has a safely repeatable no-op release`() = runTest {
        val retained = retainOperationLeases()
        retained.release()
        retained.release()
    }
}
