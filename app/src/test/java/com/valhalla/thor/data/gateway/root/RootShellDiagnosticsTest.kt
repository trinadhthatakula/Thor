// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.util.RootLifecycleDiagnostics
import com.valhalla.thor.util.RootLifecycleEvent
import com.valhalla.thor.util.RootShellPhase
import com.valhalla.thor.util.toDiagnosticLine
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootShellDiagnosticsTest {
    @Test
    fun `shell diagnostics distinguish startup failure and exact generation retirement`() = runTest {
        val events = mutableListOf<RootLifecycleEvent>()
        var failOpen = true
        var closes = 0
        val failure = IOException("sensitive startup output")
        val owner = RootShellGenerationOwner(RootShellSessionFactory {
            if (failOpen) throw failure
            object : RootShellSession {
                override val isAlive = true
                override suspend fun execute(command: String) = RootCommandResult(0, emptyList(), emptyList())
                override fun close() { closes++ }
            }
        }, StandardTestDispatcher(testScheduler), PrivilegeExecutionLane.SWEEP, RootLifecycleDiagnostics(events::add))
        assertSame(failure, runCatching { owner.healthySessionOrOpen() }.exceptionOrNull())
        failOpen = false
        val first = owner.healthySessionOrOpen()
        assertSame(first, owner.healthySessionOrOpen())
        owner.invalidateExactGeneration(first)
        val next = owner.healthySessionOrOpen()
        owner.invalidateExactGeneration(first)
        assertSame(next, owner.healthySessionOrOpen())
        assertEquals(1, closes)
        assertEquals(listOf(RootShellPhase.OPEN_STARTED, RootShellPhase.OPEN_FAILED,
            RootShellPhase.OPEN_STARTED, RootShellPhase.OPENED, RootShellPhase.RETIRE_STARTED,
            RootShellPhase.RETIRED, RootShellPhase.OPEN_STARTED, RootShellPhase.OPENED),
            events.filterIsInstance<RootLifecycleEvent.Shell>().map { it.phase })
        assertTrue(events.none { it.toDiagnosticLine().contains("sensitive") })
        owner.retireCurrentGeneration()
    }

    @Test
    fun `throwing diagnostics cannot skip transport cleanup or lane recovery`() = runTest {
        var closes = 0
        val diagnostics = RootLifecycleDiagnostics { error("unavailable diagnostic sink") }
        val owner = RootShellGenerationOwner(RootShellSessionFactory {
            object : RootShellSession {
                override val isAlive = true
                override suspend fun execute(command: String) = RootCommandResult(0, emptyList(), emptyList())
                override fun close() { closes++; throw IOException("close failure") }
            }
        }, StandardTestDispatcher(testScheduler), diagnostics = diagnostics)
        val first = owner.healthySessionOrOpen()
        owner.invalidateExactGeneration(first)
        val next = owner.healthySessionOrOpen()
        assertTrue(next.generation > first.generation)
        assertEquals(1, closes)
        val statuses = DefaultRootLaneStatusSource(diagnostics)
        statuses.markDegraded(PrivilegeExecutionLane.SWEEP, IOException("sensitive"))
        assertTrue(statuses.isDegraded(PrivilegeExecutionLane.SWEEP))
        statuses.markRecovered(PrivilegeExecutionLane.SWEEP)
        assertFalse(statuses.isDegraded(PrivilegeExecutionLane.SWEEP))
        owner.retireCurrentGeneration()
    }

    @Test
    fun `isolated uncertainty exposes acknowledgement flags without command identity or output`() = runTest {
        val events = mutableListOf<RootLifecycleEvent>()
        val outcome = isolatedOutcome(kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            terminationConfirmed = false, shellReusable = false).copy(stdout = listOf("private-output"),
            stderr = listOf("private-error"), failure = "private-failure")
        val command = RootCommand("private-command", PrivilegeExecutionContext(
            packageName = "private-package", commandClass = PrivilegeCommandClass("private-class"),
            rootExecutionPolicy = RootExecutionPolicy.ISOLATED))
        val job = TestIsolatedRootJob(outcome)
        val failure = isolatedFailure {
            executeIsolatedRootCommand(command, job, RootLifecycleDiagnostics(events::add))
        }
        assertSame(outcome, failure.outcome)
        assertEquals(1, job.submissions)
        assertEquals(1, events.size)
        val event = events.single() as RootLifecycleEvent.IsolatedOutcome
        assertEquals(RootJobOutcomeKind.TERMINATION_UNCONFIRMED, event.kind)
        assertFalse(event.terminationConfirmed)
        assertFalse(event.shellReusable)
        assertFalse(event.toDiagnosticLine().contains("private"))
    }

    @Test
    fun `cancelled isolated job emits no completion diagnostic before acknowledgement`() = runTest {
        val events = mutableListOf<RootLifecycleEvent>()
        val job = TestIsolatedRootJob()
        val caller = launch {
            executeIsolatedRootCommand(isolatedCommand(), job, RootLifecycleDiagnostics(events::add))
        }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertTrue(events.isEmpty())
        assertFalse(caller.isCompleted)
        job.completion.complete(isolatedOutcome(kind = RootJobOutcomeKind.CANCELLED, exitCode = null))
        caller.join()
        assertEquals(RootJobOutcomeKind.CANCELLED, (events.single() as RootLifecycleEvent.IsolatedOutcome).kind)
    }
}
