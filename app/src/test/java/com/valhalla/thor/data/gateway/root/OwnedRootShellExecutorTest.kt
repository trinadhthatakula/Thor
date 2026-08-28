// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellTransportDied
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
        val firstCompletion = CompletableDeferred<RootCommandResult>()
        val first = FakeRootShellSession(ExecutionSchedule.AwaitCompletion(firstCompletion))
        val second = FakeRootShellSession(ExecutionSchedule.Complete(success()))
        val factory = FakeRootShellSessionFactory(first, second)
        val executor = OwnedRootShellExecutor(
            lane = PrivilegeExecutionLane.ARCHIVE,
            sessionFactory = factory,
            ioDispatcher = cleanupDispatcher,
        )

        val cancelled = launch {
            try {
                executor.execute(command("archive.generation-one"))
            } catch (_: CancellationException) {
                // The observed behavior is the generation cleanup below.
            }
        }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        val replacement = async { executor.execute(command("archive.generation-two")) }
        runCurrent()

        assertEquals("the replacement must wait for exact-generation cleanup", 1, factory.openCount)
        cleanupDispatcher.runNext()
        runCurrent()
        assertEquals("one successor generation must open", 2, factory.openCount)
        assertEquals("generation one must close exactly once", 1, first.closeCount)
        assertEquals("generation-one cleanup must not close generation two", 0, second.closeCount)
        assertEquals(0, replacement.await().exitCode)

        firstCompletion.complete(success())
        runCurrent()
        assertEquals("a late completion must not close the replacement", 0, second.closeCount)
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
            }
        }

        override fun close() {
            closeCount += 1
            alive = false
        }
    }

    private sealed interface ExecutionSchedule {
        data class Complete(val result: RootCommandResult) : ExecutionSchedule
        data class AwaitCompletion(
            val completion: CompletableDeferred<RootCommandResult>,
        ) : ExecutionSchedule

        data object TransportDeath : ExecutionSchedule
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
