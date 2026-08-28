// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.Shell
import com.valhalla.thor.core.ThorShellConfig
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OdinRootShellSessionTest {

    @Test
    fun `factory accepts a live root shell`() = runTest {
        val shell = FakeOdinShell(status = Shell.ROOT_SHELL)

        val session = openOdinRootShellSession(testDispatcher()) { shell }

        assertTrue("a live root shell must be accepted", session.isAlive)
        assertEquals("an accepted shell must remain owned by the session", 0, shell.closeCount)
        session.close()
        assertEquals("the session must close its accepted shell", 1, shell.closeCount)
    }

    @Test
    fun `factory rejects and closes a live non-root shell`() = runTest {
        val shell = FakeOdinShell(status = Shell.NON_ROOT_SHELL)

        captureFailure<RootShellTransportException> {
            openOdinRootShellSession(testDispatcher()) { shell }
        }

        assertEquals("a non-root fallback must be closed", 1, shell.closeCount)
    }

    @Test
    fun `factory closes a shell constructed across cancellation`() = runTest {
        val shell = FakeOdinShell(status = Shell.ROOT_SHELL)

        supervisorScope {
            lateinit var opening: kotlinx.coroutines.Deferred<RootShellSession>
            opening = async {
                openOdinRootShellSession(testDispatcher()) {
                    shell.also { opening.cancel() }
                }
            }
            runCurrent()
            opening.join()

            assertTrue("the opening coroutine must remain cancelled", opening.isCancelled)
            assertEquals("a constructed but undelivered shell must be closed", 1, shell.closeCount)
        }
    }

    @Test
    fun `adapter keeps stdout and stderr separate as immutable snapshots`() = runTest {
        val shell = FakeOdinShell(
            status = Shell.ROOT_SHELL,
            resultCode = 0,
            stdoutLines = listOf(STDOUT_ONE, STDOUT_TWO),
            stderrLines = listOf(STDERR_ONE),
        )
        val session = OdinRootShellSession(shell)

        val result = session.execute(OPAQUE_COMMAND)

        assertEquals("stdout snapshot size", 2, result.stdout.size)
        assertTrue("stdout must come from its dedicated collector", result.stdout[0] == STDOUT_ONE)
        assertTrue("stdout ordering must be retained", result.stdout[1] == STDOUT_TWO)
        assertEquals("stderr snapshot size", 1, result.stderr.size)
        assertTrue("stderr must come from its dedicated collector", result.stderr[0] == STDERR_ONE)
        assertFalse("stdout and stderr collectors must be distinct", shell.collectorsAreShared)

        shell.mutateCollectors()
        assertEquals("later collector writes must not alter stdout", 2, result.stdout.size)
        assertEquals("later collector writes must not alter stderr", 1, result.stderr.size)
        val mutation = runCatching {
            @Suppress("UNCHECKED_CAST")
            (result.stdout as MutableList<String>).add(STDOUT_ONE)
        }
        assertTrue("the returned snapshot must reject mutation", mutation.isFailure)
    }

    @Test
    fun `adapter preserves an ordinary nonzero exit`() = runTest {
        val shell = FakeOdinShell(status = Shell.ROOT_SHELL, resultCode = 23)

        val result = OdinRootShellSession(shell).execute(OPAQUE_COMMAND)

        assertEquals("an executed process exit must be preserved", 23, result.exitCode)
    }

    @Test
    fun `adapter maps job not executed to transport failure`() = runTest {
        val shell = FakeOdinShell(
            status = Shell.ROOT_SHELL,
            resultCode = Shell.Result.JOB_NOT_EXECUTED,
        )

        captureFailure<RootShellTransportException> {
            OdinRootShellSession(shell).execute(OPAQUE_COMMAND)
        }
    }

    @Test
    fun `adapter rejects a dead shell before submission`() = runTest {
        val shell = FakeOdinShell(status = Shell.ROOT_SHELL, alive = false)

        captureFailure<RootShellTransportException> {
            OdinRootShellSession(shell).execute(OPAQUE_COMMAND)
        }

        assertEquals("a dead shell must receive no job", 0, shell.submissionCount)
    }

    @Test
    fun `adapter ignores a callback delivered after cancellation`() = runTest {
        val shell = FakeOdinShell(status = Shell.ROOT_SHELL, autoComplete = false)
        val pending = async { OdinRootShellSession(shell).execute(OPAQUE_COMMAND) }
        runCurrent()

        pending.cancelAndJoin()
        shell.completePendingJob()

        assertTrue("the command coroutine must remain cancelled", pending.isCancelled)
        assertEquals("the cancelled operation must submit only once", 1, shell.submissionCount)
    }

    @Test
    fun `adapter submits nothing when entered by an already-cancelled coroutine`() = runTest {
        val shell = FakeOdinShell(status = Shell.ROOT_SHELL)

        supervisorScope {
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException("synthetic cancellation"))
                OdinRootShellSession(shell).execute(OPAQUE_COMMAND)
            }
            cancelled.join()

            assertTrue("the adapter caller must remain cancelled", cancelled.isCancelled)
        }
        assertEquals(
            "an already-cancelled adapter call must submit nothing",
            0,
            shell.submissionCount
        )
    }

    @Test
    fun `central shell configuration keeps Odin verbose logging disabled`() {
        val previous = Shell.enableVerboseLogging
        try {
            Shell.enableVerboseLogging = true

            ThorShellConfig.init()

            assertFalse(
                "raw Odin command and output logging must stay disabled",
                Shell.enableVerboseLogging
            )
        } finally {
            Shell.enableVerboseLogging = previous
        }
    }

    private fun TestScope.testDispatcher(): CoroutineDispatcher =
        StandardTestDispatcher(testScheduler)

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

    private class FakeOdinShell(
        override val status: Int,
        alive: Boolean = true,
        private val resultCode: Int = 0,
        private val stdoutLines: List<String> = emptyList(),
        private val stderrLines: List<String> = emptyList(),
        private val autoComplete: Boolean = true,
    ) : Shell() {
        private var aliveState = alive
        private var pendingJob: FakeJob? = null

        var closeCount: Int = 0
            private set
        var submissionCount: Int = 0
            private set
        var collectorsAreShared: Boolean = false
            private set

        override val isAlive: Boolean get() = aliveState

        override fun newJob(): Job = FakeJob().also { pendingJob = it }

        fun completePendingJob() {
            checkNotNull(pendingJob).complete()
        }

        fun mutateCollectors() {
            pendingJob?.mutateCollectors()
        }

        override fun close() {
            closeCount += 1
            aliveState = false
        }

        override fun execTask(task: Task) = error("not used")

        override fun submitTask(task: Task) = error("not used")

        override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean {
            close()
            return true
        }

        private inner class FakeJob : Job() {
            private var stdout: MutableList<String?>? = null
            private var stderr: MutableList<String?>? = null
            private var callbackExecutor: Executor? = null
            private var callback: ResultCallback? = null

            override fun to(stdout: MutableList<String?>?): Job = apply {
                this.stdout = stdout
            }

            override fun to(
                stdout: MutableList<String?>?,
                stderr: MutableList<String?>?,
            ): Job = apply {
                this.stdout = stdout
                this.stderr = stderr
                collectorsAreShared = stdout != null && stdout === stderr
            }

            override fun add(vararg cmds: String): Job = this

            override fun add(inputStream: InputStream): Job = this

            override fun exec(): Result = result()

            override fun submit(executor: Executor?, cb: ResultCallback?) {
                submissionCount += 1
                callbackExecutor = executor
                callback = cb
                if (autoComplete) complete()
            }

            override fun enqueue(): Future<Result?> =
                CompletableFuture.completedFuture(result())

            fun complete() {
                stdoutLines.forEach { stdout?.add(it) }
                stderrLines.forEach { stderr?.add(it) }
                val delivery = Runnable { callback?.onResult(result()) }
                callbackExecutor?.execute(delivery) ?: delivery.run()
            }

            fun mutateCollectors() {
                stdout?.add(STDOUT_ONE)
                stderr?.add(STDERR_ONE)
            }

            private fun result(): Result = object : Result() {
                override val out: MutableList<String?> = mutableListOf()
                override val err: MutableList<String?> = mutableListOf()
                override val code: Int = resultCode
            }
        }
    }

    private companion object {
        const val OPAQUE_COMMAND = "opaque-command"
        const val STDOUT_ONE = "synthetic-stdout-one"
        const val STDOUT_TWO = "synthetic-stdout-two"
        const val STDERR_ONE = "synthetic-stderr-one"
    }
}
