// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.JobOutcome
import com.valhalla.superuser.JobOutcomeKind
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IsolatedRootCommandTest {
    @Test
    fun `Odin mapping preserves every field and snapshots both streams`() {
        for (kind in JobOutcomeKind.entries) {
            val stdout = mutableListOf("out")
            val stderr = mutableListOf("err")
            val source = JobOutcome(kind, null, stdout, stderr, true, false, true, false, "detail")
            val mapped = source.toRootJobOutcome()
            stdout += "late"
            stderr.clear()

            assertEquals(kind.name, mapped.kind.name)
            assertEquals(null, mapped.exitCode)
            assertEquals(listOf("out"), mapped.stdout)
            assertEquals(listOf("err"), mapped.stderr)
            assertTrue(mapped.started)
            assertFalse(mapped.terminationConfirmed)
            assertTrue(mapped.outputDrained)
            assertFalse(mapped.shellReusable)
            assertEquals("detail", mapped.failure)
            assertTrue(runCatching { (mapped.stdout as MutableList<String>).add("mutation") }.isFailure)
        }
    }

    @Test
    fun `ordinary exits retain full acknowledgement including nonzero and unusable transport`() = runTest {
        for (code in listOf(0, 23)) for (reusable in listOf(true, false)) {
            val outcome = isolatedOutcome(exitCode = code, shellReusable = reusable)
            val seen = mutableListOf<RootJobOutcome>()
            val command = isolatedCommand(observer = observer { seen += it })
            val job = TestIsolatedRootJob(outcome)

            val result = executeIsolatedRootCommand(command, job)

            assertEquals(code, result.exitCode)
            assertSame(outcome, result.rootOutcome)
            assertSame(outcome, command.rootOutcome)
            assertEquals(listOf(outcome), seen)
            assertEquals(1, job.submissions)
        }
    }

    @Test
    fun `non-exit and incomplete terminal outcomes retain metadata without success or replay`() = runTest {
        val outcomes = listOf(
            isolatedOutcome(kind = RootJobOutcomeKind.CANCELLED, exitCode = null),
            isolatedOutcome(kind = RootJobOutcomeKind.FAILED, exitCode = null, started = false),
            isolatedOutcome(kind = RootJobOutcomeKind.FAILED, exitCode = null),
            isolatedOutcome(kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED),
            isolatedOutcome(kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED, terminationConfirmed = false, shellReusable = false),
            isolatedOutcome(outputDrained = false),
            isolatedOutcome(terminationConfirmed = false),
            isolatedOutcome(exitCode = null),
        )
        for (outcome in outcomes) {
            val seen = mutableListOf<RootJobOutcome>()
            val job = TestIsolatedRootJob(outcome)
            val failure = isolatedFailure {
                executeIsolatedRootCommand(isolatedCommand(observer = observer { seen += it }), job)
            }

            assertSame(outcome, failure.outcome)
            if (outcome.kind == RootJobOutcomeKind.TERMINATION_UNCONFIRMED) {
                assertFalse(outcome.cleanupConfirmed)
            }
            assertEquals(listOf(outcome), seen)
            assertEquals(1, job.submissions)
            assertEquals(0, job.cancellations)
        }
    }

    @Test
    fun `cancellation after durable hook cancels prepared handle without dispatch`() = runTest {
        val original = CancellationException("cancel before submit")
        val job = TestIsolatedRootJob()
        val seen = mutableListOf<RootJobOutcome>()
        var caught: CancellationException? = null
        val command = isolatedCommand(observer = object : RootExecutionObserver {
            override suspend fun beforeSubmit() { currentCoroutineContext().cancel(original) }
            override suspend fun onOutcome(outcome: RootJobOutcome) { seen += outcome }
        })
        val caller = launch {
            try { executeIsolatedRootCommand(command, job) }
            catch (cancelled: CancellationException) { caught = cancelled }
        }
        caller.join()

        assertSame(original, caught)
        assertEquals(0, job.submissions)
        assertEquals(1, job.cancellations)
        assertEquals(1, seen.size)
        assertFalse(seen.single().started)
        assertTrue(seen.single().cleanupConfirmed)
        assertSame(seen.single(), original.suppressed.filterIsInstance<IsolatedRootExecutionException>().single().outcome)
    }

    @Test
    fun `failed durable hook prevents submit and still records prepared cancellation`() = runTest {
        val rejected = IOException("cannot persist admission")
        val seen = mutableListOf<RootJobOutcome>()
        val job = TestIsolatedRootJob()
        val command = isolatedCommand(observer = object : RootExecutionObserver {
            override suspend fun beforeSubmit() { throw rejected }
            override suspend fun onOutcome(outcome: RootJobOutcome) { seen += outcome }
        })

        val failure = isolatedFailure { executeIsolatedRootCommand(command, job) }

        assertSame(rejected, failure.cause)
        assertSame(seen.single(), failure.outcome)
        assertFalse(failure.outcome.started)
        assertEquals(0, job.submissions)
    }

    @Test
    fun `cancellation waits for acknowledgement and observer before ownership release`() = runTest {
        val events = mutableListOf<String>()
        val recorded = CompletableDeferred<Unit>()
        val job = TestIsolatedRootJob(events = events)
        val original = CancellationException("caller left")
        var caught: CancellationException? = null
        val command = isolatedCommand(observer = object : RootExecutionObserver {
            override suspend fun beforeSubmit() { events += "before" }
            override suspend fun onOutcome(outcome: RootJobOutcome) {
                events += "record"
                recorded.await()
            }
        })
        val caller = launch {
            try { executeIsolatedRootCommand(command, job) }
            catch (cancelled: CancellationException) { caught = cancelled }
            finally { events += "release" }
        }
        runCurrent()
        caller.cancel(original)
        runCurrent()
        assertFalse(caller.isCompleted)
        assertEquals(listOf("before", "submit", "cancel"), events)
        job.completion.complete(isolatedOutcome(kind = RootJobOutcomeKind.CANCELLED))
        runCurrent()
        assertFalse(caller.isCompleted)
        assertEquals(listOf("before", "submit", "cancel", "record"), events)
        recorded.complete(Unit)
        caller.join()

        assertRethrownCancellation(job, original, caught)
        assertEquals(listOf("before", "submit", "cancel", "record", "release"), events)
    }

    @Test
    fun `cancellation preserves acknowledgement and recorder failure on original exception`() = runTest {
        val original = CancellationException("caller cancelled")
        val recorderFailure = IOException("journal unavailable")
        val outcome = isolatedOutcome(kind = RootJobOutcomeKind.CANCELLED)
        val job = TestIsolatedRootJob()
        var caught: CancellationException? = null
        val caller = launch {
            try {
                executeIsolatedRootCommand(isolatedCommand(observer = observer { throw recorderFailure }), job)
            } catch (cancelled: CancellationException) { caught = cancelled }
        }
        runCurrent()
        caller.cancel(original)
        job.completion.complete(outcome)
        caller.join()

        assertRethrownCancellation(job, original, caught)
        val rethrown = requireNotNull(caught)
        assertTrue(rethrown.suppressed.contains(recorderFailure))
        assertSame(outcome, rethrown.suppressed.filterIsInstance<IsolatedRootExecutionException>().single().outcome)
    }

    @Test
    fun `cancellation during successful recording retains receipt without returning success`() = runTest {
        val outcome = isolatedOutcome()
        val job = TestIsolatedRootJob(outcome)
        val recorderEntered = CompletableDeferred<Unit>()
        val releaseRecorder = CompletableDeferred<Unit>()
        val seen = mutableListOf<RootJobOutcome>()
        val command = isolatedCommand(observer = observer {
            seen += it
            recorderEntered.complete(Unit)
            releaseRecorder.await()
        })
        val original = CancellationException("cancel while recording")
        var caught: CancellationException? = null
        var returnedSuccess = false
        val caller = launch {
            try {
                executeIsolatedRootCommand(command, job)
                returnedSuccess = true
            } catch (cancelled: CancellationException) { caught = cancelled }
        }
        recorderEntered.await()

        caller.cancel(original)
        runCurrent()
        assertFalse(caller.isCompleted)
        releaseRecorder.complete(Unit)
        caller.join()

        assertFalse(returnedSuccess)
        assertSame(original, caught)
        assertEquals(listOf(outcome), seen)
        assertSame(outcome, command.rootOutcome)
        assertSame(outcome, requireNotNull(caught).suppressed.filterIsInstance<IsolatedRootExecutionException>().single().outcome)
        assertEquals(1, job.submissions)
    }

    @Test
    fun `exceptional completion after submit records uncertain termination`() = runTest {
        val seen = mutableListOf<RootJobOutcome>()
        val job = TestIsolatedRootJob().also { it.completion.completeExceptionally(IOException("lost completion")) }
        val failure = isolatedFailure {
            executeIsolatedRootCommand(isolatedCommand(observer = observer { seen += it }), job)
        }

        assertSame(seen.single(), failure.outcome)
        assertEquals(RootJobOutcomeKind.TERMINATION_UNCONFIRMED, failure.outcome.kind)
        assertTrue(failure.outcome.started)
        assertFalse(failure.outcome.cleanupConfirmed)
        assertFalse(failure.outcome.shellReusable)
        assertEquals(1, job.submissions)
    }

    private fun observer(block: suspend (RootJobOutcome) -> Unit) = object : RootExecutionObserver {
        override suspend fun onOutcome(outcome: RootJobOutcome) = block(outcome)
    }
}

internal fun isolatedCommand(
    observer: RootExecutionObserver? = null,
    execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
): RootCommand = RootCommand("opaque-command", execution.copy(
    rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
    rootExecutionObserver = observer,
))

internal fun isolatedOutcome(
    kind: RootJobOutcomeKind = RootJobOutcomeKind.EXITED,
    exitCode: Int? = 0,
    started: Boolean = true,
    terminationConfirmed: Boolean = true,
    outputDrained: Boolean = true,
    shellReusable: Boolean = true,
): RootJobOutcome = RootJobOutcome(kind, exitCode, listOf("out"), listOf("err"), started,
    terminationConfirmed, outputDrained, shellReusable, "retained detail")

internal class TestIsolatedRootJob(
    outcome: RootJobOutcome? = null,
    private val events: MutableList<String> = mutableListOf(),
) : IsolatedRootJob {
    val completion = CompletableDeferred<RootJobOutcome>()
    var submissions = 0
    var cancellations = 0
    var awaitCancellation: CancellationException? = null
        private set
    init { outcome?.let(completion::complete) }
    override fun submit() { submissions++; events += "submit" }
    override fun cancel() {
        cancellations++
        events += "cancel"
        if (submissions == 0) completion.complete(isolatedOutcome(RootJobOutcomeKind.CANCELLED, null, started = false))
    }
    override suspend fun await(): RootJobOutcome = try {
        completion.await()
    } catch (cancelled: CancellationException) {
        awaitCancellation = cancelled
        throw cancelled
    }
}

internal fun assertRethrownCancellation(
    job: TestIsolatedRootJob,
    requested: CancellationException,
    rethrown: CancellationException?,
) {
    // Deferred.await may recover the coroutine stack by copying the requested exception. The
    // production helper must rethrow the exact exception it received, with the request as cause.
    assertSame(requireNotNull(job.awaitCancellation), rethrown)
    assertTrue(generateSequence(rethrown as Throwable?) { it.cause }.any { it === requested })
}

internal suspend fun isolatedFailure(block: suspend () -> Unit): IsolatedRootExecutionException {
    try { block() } catch (failure: IsolatedRootExecutionException) { return failure }
    throw AssertionError("Expected isolated execution failure")
}
