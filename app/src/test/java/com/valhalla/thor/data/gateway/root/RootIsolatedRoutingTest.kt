// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.ktx.ShellResult
import com.valhalla.thor.data.gateway.forRootCommand
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootIsolatedRoutingTest {
    @Test
    fun `command relabeling preserves explicit policy and observer`() {
        val observer = object : RootExecutionObserver {
            override suspend fun onOutcome(outcome: RootJobOutcome) {}
        }
        val original = PrivilegeExecutionContext(
            rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
            rootExecutionObserver = observer,
        )
        val copy = original.forRootCommand(PrivilegeCommandClass("different.name"))

        assertEquals(RootExecutionPolicy.ISOLATED, copy.rootExecutionPolicy)
        assertSame(observer, copy.rootExecutionObserver)
        assertEquals(RootExecutionPolicy.PERSISTENT, PrivilegeExecutionContext().rootExecutionPolicy)
    }

    @Test
    fun `MainShell policy is independent of diagnostic name and lane`() = runTest {
        for (lane in PrivilegeExecutionLane.entries) {
            val isolated = TestIsolatedRootJob(isolatedOutcome())
            var persistentSubmissions = 0
            val executor = MainShellCommandExecutor(MainShellJobFactory {
                object : MainShellPendingCommand {
                    override val isolatedJob: IsolatedRootJob = isolated
                    override fun submit(completion: (Result<ShellResult>) -> Unit) {
                        persistentSubmissions++
                        completion(Result.success(ShellResult(0, emptyList(), emptyList())))
                    }
                }
            })
            executor.execute(RootCommand("persistent", PrivilegeExecutionContext(
                lane = lane, commandClass = PrivilegeCommandClass("settings_editor.name_only"),
            )))
            val outcome = executor.execute(isolatedCommand(execution = PrivilegeExecutionContext(
                lane = lane, commandClass = PrivilegeCommandClass("unrelated.diagnostic"),
            )))

            assertEquals(1, persistentSubmissions)
            assertEquals(1, isolated.submissions)
            assertSame(isolated.completion.await(), outcome.rootOutcome)
        }
    }

    @Test
    fun `confirmed owned cancellation retains generation and original cancellation`() = runTest {
        for (lane in listOf(PrivilegeExecutionLane.ARCHIVE, PrivilegeExecutionLane.SWEEP)) {
            val first = TestIsolatedRootJob()
            val second = TestIsolatedRootJob(isolatedOutcome())
            val session = IsolatedSession(first, second)
            var opened = 0
            val executor = OwnedRootShellExecutor(lane, RootShellSessionFactory { opened++; session }, StandardTestDispatcher(testScheduler))
            val original = CancellationException("stop owned command")
            var caught: CancellationException? = null
            val caller = launch {
                try { executor.execute(isolatedCommand(execution = PrivilegeExecutionContext(lane = lane))) }
                catch (cancelled: CancellationException) { caught = cancelled }
            }
            runCurrent()
            caller.cancel(original)
            runCurrent()
            val next = async { executor.execute(isolatedCommand(execution = PrivilegeExecutionContext(lane = lane))) }
            runCurrent()
            assertFalse(caller.isCompleted)
            assertEquals(0, second.submissions)
            first.completion.complete(isolatedOutcome(kind = RootJobOutcomeKind.CANCELLED))
            caller.join()
            next.await()

            assertRethrownCancellation(first, original, caught)
            assertEquals(0, session.closed)
            assertEquals(1, opened)
            assertEquals(1, second.submissions)
        }
    }

    @Test
    fun `unusable owned outcome retires exact generation before next admission`() = runTest {
        for (outcome in listOf(
            isolatedOutcome(shellReusable = false),
            isolatedOutcome(RootJobOutcomeKind.TERMINATION_UNCONFIRMED, terminationConfirmed = false, shellReusable = false),
        )) {
            val first = IsolatedSession(TestIsolatedRootJob(outcome))
            val second = IsolatedSession(TestIsolatedRootJob(isolatedOutcome()))
            val sessions = ArrayDeque(listOf(first, second))
            val executor = OwnedRootShellExecutor(PrivilegeExecutionLane.ARCHIVE, RootShellSessionFactory { sessions.removeFirst() }, StandardTestDispatcher(testScheduler))
            val command = isolatedCommand(execution = PrivilegeExecutionContext(lane = PrivilegeExecutionLane.ARCHIVE))
            if (outcome.cleanupConfirmed) executor.execute(command)
            else isolatedFailure { executor.execute(command) }

            assertEquals(1, first.closed)
            assertEquals(0, second.closed)
            executor.execute(isolatedCommand(execution = command.execution))
            assertEquals(0, second.closed)
            assertTrue(sessions.isEmpty())
        }
    }

    @Test
    fun `unusable cancellation closes owned transport without replacing original cancellation`() = runTest {
        val job = TestIsolatedRootJob()
        val session = IsolatedSession(job)
        val executor = OwnedRootShellExecutor(PrivilegeExecutionLane.ARCHIVE, RootShellSessionFactory { session }, StandardTestDispatcher(testScheduler))
        val original = CancellationException("cancel with unusable transport")
        var caught: CancellationException? = null
        val caller = launch {
            try { executor.execute(isolatedCommand()) }
            catch (cancelled: CancellationException) { caught = cancelled }
        }
        runCurrent()
        caller.cancel(original)
        job.completion.complete(isolatedOutcome(RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            terminationConfirmed = false, shellReusable = false))
        caller.join()

        assertRethrownCancellation(job, original, caught)
        assertEquals(1, session.closed)
    }

    @Test
    fun `owned timeout retains cancelled acknowledgement and reusable generation`() = runTest {
        val job = TestIsolatedRootJob()
        val session = IsolatedSession(job)
        val executor = OwnedRootShellExecutor(PrivilegeExecutionLane.ARCHIVE, RootShellSessionFactory { session }, StandardTestDispatcher(testScheduler))
        val command = isolatedCommand(execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.ARCHIVE, commandTimeout = 1.seconds,
        ))
        val caller = async {
            try { executor.execute(command); error("Expected timeout") }
            catch (timeout: ShellCommandTimedOut) { timeout }
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(caller.isCompleted)
        assertEquals(1, job.cancellations)
        val outcome = isolatedOutcome(kind = RootJobOutcomeKind.CANCELLED)
        job.completion.complete(outcome)

        assertSame(outcome, caller.await().rootOutcome)
        assertEquals(0, session.closed)
    }

    @Test
    fun `isolated failures before and after dispatch never replay on degraded MainShell`() = runTest {
        for (started in listOf(false, true)) {
            var mainAcquisitions = 0
            val main = MainShellCommandExecutor(MainShellJobFactory {
                mainAcquisitions++
                error("No automatic replay")
            })
            val failureOutcome = isolatedOutcome(RootJobOutcomeKind.FAILED, null, started = started)
            val ownedJob = TestIsolatedRootJob(failureOutcome)
            val router = router(main, RootShellSessionFactory { IsolatedSession(ownedJob) })

            val failure = isolatedFailure {
                router.execute(isolatedCommand(execution = PrivilegeExecutionContext(lane = PrivilegeExecutionLane.ARCHIVE)))
            }

            assertSame(failureOutcome, failure.outcome)
            assertEquals(1, ownedJob.submissions)
            assertEquals(0, mainAcquisitions)
        }
    }

    @Test
    fun `degraded fallback retains isolation observer and waits before releasing MainShell`() = runTest {
        val job = TestIsolatedRootJob()
        val outcome = isolatedOutcome(kind = RootJobOutcomeKind.CANCELLED, shellReusable = false)
        val recorded = CompletableDeferred<Unit>()
        var retirements = 0
        val main = MainShellCommandExecutor(MainShellJobFactory {
            object : MainShellPendingCommand {
                override val isolatedJob: IsolatedRootJob = job
                override fun submit(completion: (Result<ShellResult>) -> Unit) = error("Isolation must survive fallback")
                override suspend fun retireTransport() { retirements++ }
            }
        })
        val router = router(main, RootShellSessionFactory { throw RootShellTransportException() })
        val original = CancellationException("cancel fallback")
        var caught: CancellationException? = null
        var observed: RootJobOutcome? = null
        val caller = launch {
            try {
                router.execute(isolatedCommand(
                    execution = PrivilegeExecutionContext(lane = PrivilegeExecutionLane.ARCHIVE),
                    observer = object : RootExecutionObserver {
                        override suspend fun onOutcome(outcome: RootJobOutcome) {
                            observed = outcome
                            recorded.await()
                        }
                    },
                ))
            } catch (cancelled: CancellationException) { caught = cancelled }
        }
        runCurrent()
        caller.cancel(original)
        job.completion.complete(outcome)
        runCurrent()
        assertFalse(caller.isCompleted)
        assertEquals(0, retirements)
        try {
            router.execute(isolatedCommand())
            error("MainShell must still be leased")
        } catch (_: ShellLaneBusy) { }
        recorded.complete(Unit)
        caller.join()

        assertRethrownCancellation(job, original, caught)
        assertSame(outcome, observed)
        assertEquals(1, retirements)
        assertEquals(1, job.submissions)
    }

    private fun TestScope.router(main: MainShellCommandExecutor, archive: RootShellSessionFactory): RootCommandRouter {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val statuses = DefaultRootLaneStatusSource()
        val admission = TestRootAdmission()
        return RootCommandRouter(main,
            OwnedRootShellExecutor(PrivilegeExecutionLane.ARCHIVE, archive, dispatcher),
            OwnedRootShellExecutor(PrivilegeExecutionLane.SWEEP, RootShellSessionFactory { error("Unused lane") }, dispatcher),
            RootFallbackCoordinator(statuses, admission), statuses, admission, dispatcher)
    }

    private class IsolatedSession(vararg jobs: IsolatedRootJob) : RootShellSession {
        private val pending = ArrayDeque(jobs.toList())
        var closed = 0
        override val isAlive: Boolean get() = closed == 0
        override suspend fun execute(command: String): RootCommandResult = error("Policy was lost")
        override suspend fun execute(command: RootCommand): RootCommandResult =
            executeIsolatedRootCommand(command, pending.removeFirst())
        override fun close() { closed++ }
    }
}
