// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneUnavailable
import com.valhalla.thor.domain.model.ShellTransportDied
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedRootShellExecutorTest {

    @Test
    fun `healthy commands reuse one generation`() = runTest {
        val session = FakeRootShellSession(
            ExecutionSchedule.Complete(success()),
            ExecutionSchedule.Complete(success()),
        )
        val factory = FakeRootShellSessionFactory(session)
        val executor = executor(factory)

        executor.execute(command("archive.backup"))
        executor.execute(command("archive.restore"))

        assertEquals("both command classes must share one live generation", 1, factory.openCount)
        assertEquals("each command class must be submitted once", 2, session.submissionCount)
        assertEquals("a healthy generation must stay open", 0, session.closeCount)
    }

    @Test
    fun `only one job is submitted per lane at a time`() = runTest {
        val firstCompletion = CompletableDeferred<RootCommandResult>()
        val session = FakeRootShellSession(
            ExecutionSchedule.AwaitCompletion(firstCompletion),
            ExecutionSchedule.Complete(success()),
        )
        val executor = executor(FakeRootShellSessionFactory(session))

        val first = async { executor.execute(command("archive.first")) }
        runCurrent()
        val second = async { executor.execute(command("archive.second")) }
        runCurrent()

        assertEquals("the second command class must wait outside Odin", 1, session.submissionCount)
        firstCompletion.complete(success())
        runCurrent()
        assertEquals(
            "the waiting command class submits after the first finishes",
            2,
            session.submissionCount
        )
        first.await()
        second.await()
    }

    @Test
    fun `waiter cancelled before mutex admission submits nothing`() = runTest {
        val firstCompletion = CompletableDeferred<RootCommandResult>()
        val session = FakeRootShellSession(
            ExecutionSchedule.AwaitCompletion(firstCompletion),
            ExecutionSchedule.Complete(success()),
        )
        val executor = executor(FakeRootShellSessionFactory(session))

        val holder = async { executor.execute(command("archive.holder")) }
        runCurrent()
        val waiter = async { executor.execute(command("archive.waiter")) }
        runCurrent()
        waiter.cancelAndJoin()

        assertEquals(
            "the cancelled command class must never reach the session",
            1,
            session.submissionCount
        )
        firstCompletion.complete(success())
        holder.await()
    }

    @Test
    fun `cancellation after open but before submission closes without submitting`() = runTest {
        val session = FakeRootShellSession(ExecutionSchedule.Complete(success()))
        val factory = RootShellSessionFactory {
            currentCoroutineContext().cancel(CancellationException("synthetic cancellation"))
            session
        }
        val executor = executor(factory)

        val commandJob = launch {
            executor.execute(command("archive.cancelled-after-open"))
        }
        runCurrent()
        commandJob.join()

        assertTrue("the command coroutine must remain cancelled", commandJob.isCancelled)
        assertEquals("post-open cancellation must submit nothing", 0, session.submissionCount)
        assertEquals(
            "post-open cancellation must close the acquired generation",
            1,
            session.closeCount
        )
    }

    @Test
    fun `nonzero command exit does not replace a healthy generation`() = runTest {
        val session = FakeRootShellSession(
            ExecutionSchedule.Complete(result(exitCode = 23)),
            ExecutionSchedule.Complete(success()),
        )
        val factory = FakeRootShellSessionFactory(session)
        val executor = executor(factory)

        val failedCommand = executor.execute(command("archive.nonzero"))
        executor.execute(command("archive.after-nonzero"))

        assertEquals("the process exit must be preserved", 23, failedCommand.exitCode)
        assertEquals("a normal process failure must retain the generation", 1, factory.openCount)
        assertEquals("a normal process failure must not close the session", 0, session.closeCount)
    }

    @Test
    fun `open failure is unavailable while post-open failure is transport death`() = runTest {
        val openCause = IllegalStateException("synthetic open failure")
        val unavailable = captureFailure<ShellLaneUnavailable> {
            executor { throw RootShellTransportException(openCause) }
                .execute(command("archive.open"))
        }

        val session = FakeRootShellSession(ExecutionSchedule.TransportDeath)
        val died = captureFailure<ShellTransportDied> {
            executor(FakeRootShellSessionFactory(session))
                .execute(command("archive.transport"))
        }

        assertEquals(PrivilegeExecutionLane.ARCHIVE, unavailable.lane)
        assertTrue(
            "the open cause must remain available for diagnostics",
            unavailable.cause === openCause
        )
        assertEquals(PrivilegeExecutionLane.ARCHIVE, died.lane)
        assertTrue(
            "post-open transport death must retain its typed cause",
            died.cause is RootShellTransportException
        )
        assertEquals("post-open transport death must close its generation", 1, session.closeCount)
    }

    @Test
    fun `transport death closes and replaces only the used generation`() = runTest {
        val first = FakeRootShellSession(ExecutionSchedule.TransportDeath)
        val second = FakeRootShellSession(ExecutionSchedule.Complete(success()))
        val factory = FakeRootShellSessionFactory(first, second)
        val executor = executor(factory)

        val failure = captureFailure<ShellTransportDied> {
            executor.execute(command("archive.transport"))
        }
        val replacementResult = executor.execute(command("archive.replacement"))

        assertEquals(PrivilegeExecutionLane.ARCHIVE, failure.lane)
        assertEquals("transport death must close its generation", 1, first.closeCount)
        assertEquals("a successor command must open one replacement", 2, factory.openCount)
        assertEquals(
            "cleanup from the failed generation must not close its replacement",
            0,
            second.closeCount
        )
        assertEquals(0, replacementResult.exitCode)
    }

    @Test
    fun `close failure preserves typed transport result and releases ownership`() = runTest {
        val first = FakeRootShellSession(
            ExecutionSchedule.TransportDeath,
            closeFailure = IllegalStateException("synthetic close failure"),
        )
        val second = FakeRootShellSession(ExecutionSchedule.Complete(success()))
        val factory = FakeRootShellSessionFactory(first, second)
        val executor = executor(factory)

        val failure = captureFailure<ShellTransportDied> {
            executor.execute(command("archive.transport-close-failure"))
        }
        val replacementResult = executor.execute(command("archive.after-close-failure"))

        assertEquals(PrivilegeExecutionLane.ARCHIVE, failure.lane)
        assertEquals("the failed close must still be attempted once", 1, first.closeCount)
        assertEquals("ownership must advance to one replacement", 2, factory.openCount)
        assertEquals("the replacement generation must remain open", 0, second.closeCount)
        assertEquals(0, replacementResult.exitCode)
    }

    @Test
    fun `external cancellation closes used generation and rethrows cancellation`() = runTest {
        val completion = CompletableDeferred<RootCommandResult>()
        val session = FakeRootShellSession(ExecutionSchedule.AwaitCompletion(completion))
        val executor = executor(FakeRootShellSessionFactory(session))

        val commandJob = launch {
            executor.execute(command("archive.cancelled"))
        }
        runCurrent()
        commandJob.cancelAndJoin()

        assertTrue("the command coroutine must remain cancelled", commandJob.isCancelled)
        assertEquals("cancellation must close the generation in use", 1, session.closeCount)
    }

    @Test
    fun `session cancellation reports ShellCommandCancelled with command class`() = runTest {
        val session = FakeRootShellSession(ExecutionSchedule.Cancelled)
        val executor = executor(FakeRootShellSessionFactory(session))
        val commandClass = PrivilegeCommandClass("archive.cancelled")

        val failure = captureFailure<ShellCommandCancelled> {
            executor.execute(command(commandClass.value))
        }

        assertEquals(commandClass, failure.commandClass)
        assertEquals("typed cancellation must close the generation in use", 1, session.closeCount)
    }

    @Test
    fun `null timeout runs while explicit zero times out immediately`() = runTest {
        val session = FakeRootShellSession(
            ExecutionSchedule.Complete(success()),
            ExecutionSchedule.Complete(success()),
        )
        val factory = FakeRootShellSessionFactory(session)
        val executor = executor(factory)

        val unbounded = executor.execute(command("archive.no-timeout", timeout = null))
        val failure = captureFailure<ShellCommandTimedOut> {
            executor.execute(command("archive.zero-timeout", timeout = Duration.ZERO))
        }

        assertEquals("a null timeout must permit execution", 0, unbounded.exitCode)
        assertEquals(
            PrivilegeCommandClass("archive.zero-timeout"),
            failure.commandClass,
        )
        assertEquals(
            "zero timeout must expire before a second submission",
            1,
            session.submissionCount
        )
        assertEquals(
            "an explicit zero timeout must close the used generation",
            1,
            session.closeCount
        )
    }

    @Test
    fun `outer timeout with no command timeout reports typed cancellation`() = runTest {
        assertOuterTimeoutIsTypedCancellation(
            commandClass = PrivilegeCommandClass("archive.outer-timeout-unbounded"),
            commandTimeout = null,
        )
    }

    @Test
    fun `outer timeout earlier than command timeout reports typed cancellation`() = runTest {
        assertOuterTimeoutIsTypedCancellation(
            commandClass = PrivilegeCommandClass("sweep.outer-timeout-first"),
            commandTimeout = 30.seconds,
        )
    }

    @Test
    fun `deadline closes used generation and reports ShellCommandTimedOut`() = runTest {
        val completion = CompletableDeferred<RootCommandResult>()
        val session = FakeRootShellSession(ExecutionSchedule.AwaitCompletion(completion))
        val executor = executor(FakeRootShellSessionFactory(session))
        val commandClass = PrivilegeCommandClass("sweep.deadline")

        supervisorScope {
            val pending = async {
                executor.execute(command(commandClass.value, timeout = 30.seconds))
            }
            runCurrent()
            advanceTimeBy(30.seconds)
            runCurrent()
            val failure = captureFailure<ShellCommandTimedOut> { pending.await() }

            assertEquals(commandClass, failure.commandClass)
            assertEquals(
                "a proven deadline must close the generation in use",
                1,
                session.closeCount
            )
        }
    }

    @Test
    fun `late cleanup from generation one cannot close generation two`() = runTest {
        val cleanupDispatcher = QueuedDispatcher()
        val first = FakeRootShellSession(ExecutionSchedule.Complete(success()))
        val second = FakeRootShellSession(ExecutionSchedule.Complete(success()))
        val factory = FakeRootShellSessionFactory(first, second)
        val owner = RootShellGenerationOwner(
            sessionFactory = factory,
            ioDispatcher = cleanupDispatcher,
        )
        val generationOne = owner.healthySessionOrOpen()

        val cleanup = async { owner.invalidateExactGeneration(generationOne) }
        runCurrent()
        val generationTwo = owner.healthySessionOrOpen()

        assertEquals(
            "one successor generation must exist before old cleanup runs",
            2,
            factory.openCount
        )
        assertEquals("generation one must still await queued close", 0, first.closeCount)
        assertEquals("generation two must remain open", 0, second.closeCount)

        cleanupDispatcher.runNext()
        runCurrent()
        cleanup.await()
        owner.invalidateExactGeneration(generationOne)

        assertEquals("generation one must close exactly once", 1, first.closeCount)
        assertEquals(
            "late generation-one invalidation must not close generation two",
            0,
            second.closeCount
        )
        assertTrue(
            "the replacement lease must own generation two",
            generationTwo.session === second
        )
    }

    @Test
    fun `mutating command is never retried after unknown transport outcome`() = runTest {
        val first = FakeRootShellSession(ExecutionSchedule.TransportDeath)
        val unusedReplacement = FakeRootShellSession(ExecutionSchedule.Complete(success()))
        val factory = FakeRootShellSessionFactory(first, unusedReplacement)
        val executor = executor(factory)

        captureFailure<ShellTransportDied> {
            executor.execute(command("package.clear-data"))
        }

        assertEquals(
            "an unknown mutation outcome must have one submission",
            1,
            first.submissionCount
        )
        assertEquals(
            "an unknown mutation outcome must not open a retry generation",
            1,
            factory.openCount
        )
        assertEquals(
            "the unused replacement must receive no command",
            0,
            unusedReplacement.submissionCount
        )
    }

    private suspend fun TestScope.assertOuterTimeoutIsTypedCancellation(
        commandClass: PrivilegeCommandClass,
        commandTimeout: Duration?,
    ) {
        val completion = CompletableDeferred<RootCommandResult>()
        val session = FakeRootShellSession(ExecutionSchedule.AwaitCompletion(completion))
        val factory = FakeRootShellSessionFactory(session)
        val executor = executor(factory)

        supervisorScope {
            val executorFailure = CompletableDeferred<Throwable>()
            val pending = launch {
                try {
                    withTimeout(10.seconds) {
                        try {
                            executor.execute(command(commandClass.value, timeout = commandTimeout))
                        } catch (failure: Throwable) {
                            executorFailure.complete(failure)
                            throw failure
                        }
                    }
                } catch (_: Throwable) {
                    // The assertion inspects the failure at the executor boundary above. The outer
                    // timeout may retain its own terminal cancellation after the block exits.
                }
            }
            runCurrent()
            advanceTimeBy(10.seconds)
            runCurrent()
            val failure = executorFailure.await()

            assertTrue(
                "an outer timeout must map to ShellCommandCancelled, was ${failure::class.java.name}",
                failure is ShellCommandCancelled,
            )
            assertEquals(commandClass, (failure as ShellCommandCancelled).commandClass)
            pending.join()
        }
        assertEquals("an outer timeout must close the generation in use", 1, session.closeCount)
        assertEquals("an outer timeout must not retry the command", 1, session.submissionCount)
        assertEquals("an outer timeout must not open a retry generation", 1, factory.openCount)
    }

    private fun TestScope.executor(factory: RootShellSessionFactory): OwnedRootShellExecutor =
        OwnedRootShellExecutor(
            lane = PrivilegeExecutionLane.ARCHIVE,
            sessionFactory = factory,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun command(
        commandClass: String,
        timeout: Duration? = null,
    ): RootCommand = RootCommand(
        text = "opaque-command",
        execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.ARCHIVE,
            commandClass = PrivilegeCommandClass(commandClass),
            commandTimeout = timeout,
        ),
    )

    private suspend inline fun <reified T : Throwable> captureFailure(
        noinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (failure: Throwable) {
            assertTrue("expected ${T::class.java.simpleName}", failure is T)
            return failure as T
        }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() {
            assertFalse("cleanup must be queued before it is released", tasks.isEmpty())
            tasks.removeFirst().run()
        }
    }

    private class FakeRootShellSessionFactory(
        vararg sessions: FakeRootShellSession,
    ) : RootShellSessionFactory {
        private val scheduledSessions = ArrayDeque(sessions.toList())
        val openedSessions = mutableListOf<FakeRootShellSession>()
        val openCount: Int get() = openedSessions.size

        override suspend fun open(): RootShellSession {
            assertFalse(
                "a fake session must be scheduled before opening",
                scheduledSessions.isEmpty()
            )
            return scheduledSessions.removeFirst().also(openedSessions::add)
        }
    }

    private class FakeRootShellSession(
        vararg schedules: ExecutionSchedule,
        private val closeFailure: Exception? = null,
    ) : RootShellSession {
        private val scheduledExecutions = ArrayDeque(schedules.toList())
        private var alive = true

        var submissionCount: Int = 0
            private set
        var closeCount: Int = 0
            private set

        override val isAlive: Boolean get() = alive

        override suspend fun execute(command: String): RootCommandResult {
            submissionCount += 1
            assertFalse(
                "a fake callback must be scheduled before submission",
                scheduledExecutions.isEmpty()
            )
            return when (val schedule = scheduledExecutions.removeFirst()) {
                is ExecutionSchedule.Complete -> schedule.result
                is ExecutionSchedule.AwaitCompletion -> schedule.completion.await()
                ExecutionSchedule.TransportDeath -> {
                    alive = false
                    throw RootShellTransportException()
                }

                ExecutionSchedule.Cancelled -> throw CancellationException("synthetic cancellation")
            }
        }

        override fun close() {
            closeCount += 1
            alive = false
            closeFailure?.let { throw it }
        }
    }

    private sealed interface ExecutionSchedule {
        data class Complete(val result: RootCommandResult) : ExecutionSchedule
        data class AwaitCompletion(
            val completion: CompletableDeferred<RootCommandResult>,
        ) : ExecutionSchedule

        data object TransportDeath : ExecutionSchedule
        data object Cancelled : ExecutionSchedule
    }

    private companion object {
        fun success(): RootCommandResult = result(exitCode = 0)

        fun result(exitCode: Int): RootCommandResult = RootCommandResult(
            exitCode = exitCode,
            stdout = emptyList(),
            stderr = emptyList(),
        )
    }
}
