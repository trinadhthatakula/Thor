// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.superuser.ktx.ShellResult
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellTransportDied
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootCommandRouterTest {

    @Test
    fun `interactive always routes to MainShell`() = runTest {
        val main = FakeShellRepository { ShellResult(23, listOf("stdout"), listOf("stderr")) }
        val fixture = fixture(main = main)

        val result =
            fixture.router.execute(command(PrivilegeExecutionLane.INTERACTIVE, "package.disable"))

        assertEquals(23, result.exitCode)
        assertEquals(listOf("stdout"), result.stdout)
        assertEquals(listOf("stderr"), result.stderr)
        assertEquals(1, main.submissionCount)
        assertEquals(0, fixture.archiveFactory.openCount)
        assertEquals(0, fixture.sweepFactory.openCount)
    }

    @Test
    fun `MainShell job not executed reports transport death`() = runTest {
        val fixture = fixture(
            main = FakeShellRepository {
                ShellResult(
                    ShellResult.JOB_NOT_EXECUTED,
                    emptyList(),
                    listOf("transport unavailable")
                )
            },
        )

        val failure = captureFailure<ShellTransportDied> {
            fixture.router.execute(command(PrivilegeExecutionLane.INTERACTIVE, "package.disable"))
        }

        assertEquals(PrivilegeExecutionLane.INTERACTIVE, failure.lane)
    }

    @Test
    fun `archive and sweep route to different owned executors`() = runTest {
        val archiveSession = FakeRootShellSession { success() }
        val sweepSession = FakeRootShellSession { success() }
        val fixture = fixture(
            archiveFactory = RecordingSessionFactory { archiveSession },
            sweepFactory = RecordingSessionFactory { sweepSession },
        )

        fixture.router.execute(command(PrivilegeExecutionLane.ARCHIVE, "archive.backup", "archive"))
        fixture.router.execute(command(PrivilegeExecutionLane.SWEEP, "sweep.clear_cache", "sweep"))

        assertEquals(listOf("archive"), archiveSession.commands)
        assertEquals(listOf("sweep"), sweepSession.commands)
        assertEquals(0, fixture.main.submissionCount)
    }

    @Test
    fun `archive factory failure degrades archive only`() = runTest {
        val openCause = IllegalStateException("dedicated archive unavailable")
        val archiveFactory = RecordingSessionFactory {
            throw RootShellTransportException(openCause)
        }
        val sweepSession = FakeRootShellSession { success() }
        val fixture = fixture(
            archiveFactory = archiveFactory,
            sweepFactory = RecordingSessionFactory { sweepSession },
        )

        fixture.router.execute(command(PrivilegeExecutionLane.ARCHIVE, "archive.backup"))
        fixture.router.execute(command(PrivilegeExecutionLane.ARCHIVE, "archive.restore"))
        fixture.router.execute(command(PrivilegeExecutionLane.SWEEP, "sweep.clear_cache"))

        assertEquals("a degraded lane must never try to open again", 1, archiveFactory.openCount)
        assertEquals("both archive commands must use fallback", 2, fixture.main.submissionCount)
        assertEquals(1, sweepSession.submissionCount)
        assertEquals(
            RootLaneMode.DEGRADED,
            fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).mode,
        )
        assertEquals(
            RootLaneMode.ISOLATED,
            fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.SWEEP).mode,
        )
        assertTrue(
            "only the first open cause must be retained for diagnostics",
            fixture.statuses.degradationCause(PrivilegeExecutionLane.ARCHIVE) === openCause,
        )
        assertNull(fixture.statuses.degradationCause(PrivilegeExecutionLane.SWEEP))
    }

    @Test
    fun `post-open transport death fails without degrading or replaying`() = runTest {
        val transportCause = IllegalStateException("dedicated transport died")
        val archiveSession = FakeRootShellSession {
            throw RootShellTransportException(transportCause)
        }
        val fixture = fixture(
            archiveFactory = RecordingSessionFactory { archiveSession },
        )

        val failure = captureFailure<ShellTransportDied> {
            fixture.router.execute(command(PrivilegeExecutionLane.ARCHIVE, "archive.backup"))
        }

        assertEquals(PrivilegeExecutionLane.ARCHIVE, failure.lane)
        assertTrue(failure.cause === transportCause)
        assertEquals(
            RootLaneMode.ISOLATED,
            fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).mode
        )
        assertEquals(
            "the unknown command outcome must not replay on MainShell",
            0,
            fixture.main.submissionCount
        )
        assertEquals(1, archiveSession.submissionCount)
    }

    @Test
    fun `degraded background lane uses coordinated MainShell`() = runTest {
        val fixture = fixture(
            archiveFactory = unavailableFactory("archive unavailable"),
        )

        fixture.router.execute(command(PrivilegeExecutionLane.ARCHIVE, "archive.backup"))

        assertEquals(1, fixture.main.submissionCount)
        assertEquals(
            RootLaneMode.DEGRADED,
            fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).mode,
        )
        assertNull(
            fixture.statuses.statuses.value
                .getValue(PrivilegeExecutionLane.ARCHIVE)
                .fallbackOwner,
        )
    }

    @Test
    fun `interactive command is promptly rejected while degraded archive owns MainShell`() =
        runTest {
            assertInteractiveRejectedWhileFallbackOwns(PrivilegeExecutionLane.ARCHIVE)
        }

    @Test
    fun `interactive command is promptly rejected while degraded sweep owns MainShell`() = runTest {
        assertInteractiveRejectedWhileFallbackOwns(PrivilegeExecutionLane.SWEEP)
    }

    @Test
    fun `sweep and archive fallback serialize when both lanes are degraded`() = runTest {
        val archiveCompletion = CompletableDeferred<ShellResult>()
        val sweepCompletion = CompletableDeferred<ShellResult>()
        val completions = ArrayDeque(listOf(archiveCompletion, sweepCompletion))
        val fixture = fixture(
            main = FakeShellRepository { completions.removeFirst().await() },
            archiveFactory = unavailableFactory("archive unavailable"),
            sweepFactory = unavailableFactory("sweep unavailable"),
        )

        val archive = async {
            fixture.router.execute(command(PrivilegeExecutionLane.ARCHIVE, "archive.backup"))
        }
        runCurrent()
        val sweep = async {
            fixture.router.execute(command(PrivilegeExecutionLane.SWEEP, "sweep.clear_cache"))
        }
        runCurrent()

        assertEquals("only one fallback may enter MainShell", 1, fixture.main.submissionCount)
        archiveCompletion.complete(shellSuccess())
        runCurrent()
        assertEquals(
            "the waiting fallback submits only after release",
            2,
            fixture.main.submissionCount
        )
        sweepCompletion.complete(shellSuccess())

        archive.await()
        sweep.await()
    }

    @Test
    fun `cancelled degraded command drains active callback before releasing MainShell`() = runTest {
        val callback = CompletableDeferred<ShellResult>()
        val events = mutableListOf<String>()
        val fixture = fixture(
            main = FakeShellRepository {
                events += "submitted"
                callback.await().also { events += "callback-drained" }
            },
            archiveFactory = unavailableFactory("archive unavailable"),
        )
        val terminal = CompletableDeferred<Throwable>()
        val pending = launch {
            try {
                fixture.router.execute(command(PrivilegeExecutionLane.ARCHIVE, "archive.backup"))
            } catch (failure: Throwable) {
                events += "terminal"
                terminal.complete(failure)
                throw failure
            }
        }
        runCurrent()

        pending.cancel(CancellationException("user cancelled"))
        runCurrent()

        assertFalse(
            "cancellation must not publish before the callback drains",
            terminal.isCompleted
        )
        val busy = captureFailure<ShellLaneBusy> {
            fixture.router.execute(command(PrivilegeExecutionLane.INTERACTIVE, "package.unfreeze"))
        }
        assertEquals(PrivilegeExecutionLane.ARCHIVE, busy.owner)

        callback.complete(shellSuccess())
        runCurrent()

        assertTrue(terminal.await() is ShellCommandCancelled)
        assertEquals(listOf("submitted", "callback-drained", "terminal"), events)
        pending.join()
    }

    @Test
    fun `timed out degraded sweep cannot execute after terminal failure is published`() = runTest {
        val callback = CompletableDeferred<ShellResult>()
        val events = mutableListOf<String>()
        val fixture = fixture(
            main = FakeShellRepository {
                events += "submitted"
                callback.await().also { events += "callback-drained" }
            },
            sweepFactory = unavailableFactory("sweep unavailable"),
        )

        supervisorScope {
            val pending = async {
                try {
                    fixture.router.execute(
                        command(
                            lane = PrivilegeExecutionLane.SWEEP,
                            commandClass = "sweep.clear_cache",
                            timeout = PrivilegeExecutionTimeouts.SWEEP_COMMAND,
                        ),
                    )
                } catch (failure: Throwable) {
                    events += "terminal"
                    throw failure
                }
            }
            runCurrent()
            advanceTimeBy(PrivilegeExecutionTimeouts.SWEEP_COMMAND)
            runCurrent()

            assertFalse(
                "the timeout must remain pending while its callback can still run",
                pending.isCompleted
            )
            assertEquals(1, fixture.main.submissionCount)

            callback.complete(shellSuccess())
            runCurrent()
            val failure = captureFailure<ShellCommandTimedOut> { pending.await() }

            assertEquals(PrivilegeCommandClass("sweep.clear_cache"), failure.commandClass)
            assertEquals(listOf("submitted", "callback-drained", "terminal"), events)
            assertEquals(
                "a timed-out mutation must never be replayed",
                1,
                fixture.main.submissionCount
            )
        }
    }

    @Test
    fun `foreign timeout during degraded fallback remains cancellation after drain`() = runTest {
        val callback = CompletableDeferred<ShellResult>()
        val fixture = fixture(
            main = FakeShellRepository { callback.await() },
            archiveFactory = unavailableFactory("archive unavailable"),
        )
        val terminal = CompletableDeferred<Throwable>()

        val pending = launch {
            try {
                withTimeout(5.seconds) {
                    try {
                        fixture.router.execute(
                            command(
                                lane = PrivilegeExecutionLane.ARCHIVE,
                                commandClass = "archive.backup",
                                timeout = 30.seconds,
                            ),
                        )
                    } catch (failure: Throwable) {
                        terminal.complete(failure)
                        throw failure
                    }
                }
            } catch (_: Throwable) {
                // The outer timeout retains its own terminal cancellation after the block exits.
            }
        }
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertFalse("an enclosing timeout must also wait for callback drain", terminal.isCompleted)
        callback.complete(shellSuccess())
        runCurrent()

        assertTrue(terminal.await() is ShellCommandCancelled)
        pending.join()
    }

    @Test
    fun `lane status never includes raw command or output`() = runTest {
        val callback = CompletableDeferred<ShellResult>()
        val fixture = fixture(
            main = FakeShellRepository { callback.await() },
            archiveFactory = unavailableFactory("archive unavailable without command data"),
        )
        val rawCommand = "tar /private/archive --passphrase=hunter2"
        val rawOutput = "restored /private/archive"
        val pending = async {
            fixture.router.execute(
                command(
                    lane = PrivilegeExecutionLane.ARCHIVE,
                    commandClass = "archive.extract",
                    text = rawCommand,
                ),
            )
        }
        runCurrent()

        val activeStatus = fixture.statuses.statuses.value
            .getValue(PrivilegeExecutionLane.ARCHIVE)
        assertEquals(RootLaneMode.DEGRADED, activeStatus.mode)
        assertEquals(PrivilegeCommandClass("archive.extract"), activeStatus.activeCommandClass)
        assertEquals(PrivilegeExecutionLane.ARCHIVE, activeStatus.fallbackOwner)
        assertFalse(activeStatus.toString().contains(rawCommand))
        assertFalse(activeStatus.toString().contains("hunter2"))

        callback.complete(ShellResult(0, listOf(rawOutput), emptyList()))
        pending.await()

        val terminalStatus = fixture.statuses.statuses.value
            .getValue(PrivilegeExecutionLane.ARCHIVE)
        assertNull(terminalStatus.activeCommandClass)
        assertNull(terminalStatus.fallbackOwner)
        assertFalse(terminalStatus.toString().contains(rawCommand))
        assertFalse(terminalStatus.toString().contains(rawOutput))
    }

    private suspend fun TestScope.assertInteractiveRejectedWhileFallbackOwns(
        lane: PrivilegeExecutionLane,
    ) {
        val callback = CompletableDeferred<ShellResult>()
        val fixture = fixture(
            main = FakeShellRepository { callback.await() },
            archiveFactory = if (lane == PrivilegeExecutionLane.ARCHIVE) {
                unavailableFactory("archive unavailable")
            } else {
                RecordingSessionFactory { FakeRootShellSession { success() } }
            },
            sweepFactory = if (lane == PrivilegeExecutionLane.SWEEP) {
                unavailableFactory("sweep unavailable")
            } else {
                RecordingSessionFactory { FakeRootShellSession { success() } }
            },
        )
        val background = async { fixture.router.execute(command(lane, "background.command")) }
        runCurrent()

        val failure = captureFailure<ShellLaneBusy> {
            fixture.router.execute(command(PrivilegeExecutionLane.INTERACTIVE, "package.unfreeze"))
        }

        assertEquals(lane, failure.owner)
        assertFalse(
            "interactive rejection must not enqueue behind fallback",
            background.isCompleted
        )
        assertEquals(
            "only the background command may enter MainShell",
            1,
            fixture.main.submissionCount
        )
        callback.complete(shellSuccess())
        background.await()
    }

    private fun TestScope.fixture(
        main: FakeShellRepository = FakeShellRepository { shellSuccess() },
        archiveFactory: RecordingSessionFactory = RecordingSessionFactory {
            FakeRootShellSession { success() }
        },
        sweepFactory: RecordingSessionFactory = RecordingSessionFactory {
            FakeRootShellSession { success() }
        },
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val statuses = DefaultRootLaneStatusSource()
        val mainExecutor = MainShellCommandExecutor(main)
        val fallback = RootFallbackCoordinator(statuses)
        val router = RootCommandRouter(
            main = mainExecutor,
            archive = OwnedRootShellExecutor(
                lane = PrivilegeExecutionLane.ARCHIVE,
                sessionFactory = archiveFactory,
                ioDispatcher = dispatcher,
            ),
            sweep = OwnedRootShellExecutor(
                lane = PrivilegeExecutionLane.SWEEP,
                sessionFactory = sweepFactory,
                ioDispatcher = dispatcher,
            ),
            fallback = fallback,
            statuses = statuses,
        )
        return Fixture(router, statuses, main, archiveFactory, sweepFactory)
    }

    private fun unavailableFactory(message: String): RecordingSessionFactory =
        RecordingSessionFactory {
            throw RootShellTransportException(IllegalStateException(message))
        }

    private fun command(
        lane: PrivilegeExecutionLane,
        commandClass: String,
        text: String = "opaque-command",
        timeout: Duration? = null,
    ): RootCommand = RootCommand(
        text = text,
        execution = PrivilegeExecutionContext(
            lane = lane,
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
            assertTrue(
                "expected ${T::class.java.simpleName}, was ${failure::class.java.simpleName}",
                failure is T
            )
            return failure as T
        }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }

    private data class Fixture(
        val router: RootCommandRouter,
        val statuses: DefaultRootLaneStatusSource,
        val main: FakeShellRepository,
        val archiveFactory: RecordingSessionFactory,
        val sweepFactory: RecordingSessionFactory,
    )

    private class RecordingSessionFactory(
        private val openBlock: suspend () -> RootShellSession,
    ) : RootShellSessionFactory {
        var openCount: Int = 0
            private set

        override suspend fun open(): RootShellSession {
            openCount += 1
            return openBlock()
        }
    }

    private class FakeRootShellSession(
        private val executeBlock: suspend (String) -> RootCommandResult,
    ) : RootShellSession {
        private var alive = true
        val commands = mutableListOf<String>()
        val submissionCount: Int get() = commands.size

        override val isAlive: Boolean get() = alive

        override suspend fun execute(command: String): RootCommandResult {
            commands += command
            return executeBlock(command)
        }

        override fun close() {
            alive = false
        }
    }

    private class FakeShellRepository(
        private val executeBlock: suspend (String) -> ShellResult,
    ) : ShellRepository {
        val commands = mutableListOf<String>()
        val submissionCount: Int get() = commands.size

        override suspend fun isRootGranted(): Boolean = true

        override suspend fun exec(vararg commands: String): ShellResult {
            require(commands.size == 1)
            return commands.single().let { command ->
                this.commands += command
                executeBlock(command)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override suspend fun runCommand(command: String): Result<List<String>> =
            exec(command).toLegacyResult()

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override suspend fun runCommands(vararg commands: String): Result<List<String>> =
            exec(*commands).toLegacyResult()

        private fun ShellResult.toLegacyResult(): Result<List<String>> = if (isSuccess) {
            Result.success(stdout)
        } else {
            Result.failure(IOException("synthetic shell failure $code"))
        }
    }

    private companion object {
        fun shellSuccess(): ShellResult = ShellResult(0, emptyList(), emptyList())

        fun success(): RootCommandResult = RootCommandResult(0, emptyList(), emptyList())
    }
}
