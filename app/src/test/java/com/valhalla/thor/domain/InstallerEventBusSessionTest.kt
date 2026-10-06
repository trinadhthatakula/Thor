// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain

import com.valhalla.thor.util.UiText
import com.valhalla.thor.domain.repository.withRetainableOperationLease
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallerEventBusSessionTest {
    @Test
    fun `terminal callback before awaiting remains available and publishes UI state`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)

        bus.emitSessionResult(41, completion.token, InstallState.Success)

        assertEquals(41, completion.sessionId)
        assertSame(InstallState.Success, completion.await())
        assertSame(InstallState.Success, bus.latest)
    }

    @Test
    fun `only matching session and token can settle a ticket`() = runTest {
        val bus = InstallerEventBus()
        val first = bus.registerSession(41)
        val other = bus.registerSession(42)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { first.await() }
        try {
            for ((sessionId, token) in listOf(41 to null, 41 to other.token, 42 to first.token)) {
                bus.emitSessionResult(sessionId, token, InstallState.Success)
                runCurrent()
                assertFalse(waiter.isCompleted)
            }
            bus.emitSessionResult(41, first.token, InstallState.Success)
            assertSame(InstallState.Success, waiter.await())
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(first)
            bus.unregisterSession(other)
        }
    }

    @Test
    fun `uncorrelated UI terminal states and correlated progress do not settle a ticket`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        try {
            bus.emit(InstallState.Success)
            bus.emit(InstallState.Error(UiText.DynamicString("Another install failed")))
            for (state in listOf(InstallState.Parsing, InstallState.Installing(1f), InstallState.UserConfirmationRequired)) {
                bus.emitSessionResult(41, completion.token, state)
            }
            runCurrent()
            assertFalse(waiter.isCompleted)
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(completion)
        }
    }

    @Test
    fun `the first correlated terminal result cannot be overwritten`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val failure = InstallState.Error(UiText.DynamicString("Rejected"))

        bus.emitSessionResult(41, completion.token, failure)
        bus.emitSessionResult(41, completion.token, InstallState.Success)

        assertSame(failure, completion.await())
    }

    @Test
    fun `unregistering an older attempt cannot remove a replacement with the same session ID`() = runTest {
        val bus = InstallerEventBus()
        val old = bus.registerSession(41)
        val replacement = bus.registerSession(41)
        assertNotEquals(old.token, replacement.token)
        bus.unregisterSession(old)
        val oldWaiter = async(start = CoroutineStart.UNDISPATCHED) { old.await() }
        val newWaiter = async(start = CoroutineStart.UNDISPATCHED) { replacement.await() }
        try {
            bus.emitSessionResult(41, old.token, InstallState.Success)
            runCurrent()
            assertFalse(oldWaiter.isCompleted)
            assertFalse(newWaiter.isCompleted)

            bus.unregisterSession(old)
            bus.emitSessionResult(41, replacement.token, InstallState.Success)
            assertSame(InstallState.Success, newWaiter.await())
        } finally {
            oldWaiter.cancelAndJoin()
            newWaiter.cancelAndJoin()
            bus.unregisterSession(replacement)
        }
    }

    @Test
    fun `cancelling a waiter and resetting presentation do not cancel the session completion`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        cancelledWaiter.cancelAndJoin()
        bus.reset()

        bus.emitSessionResult(41, completion.token, InstallState.Success)

        assertSame(InstallState.Success, completion.await())
    }

    @Test
    fun `a detached cancelled waiter retains its lease until exactly one matching terminal callback`() = runTest {
        val bus = InstallerEventBus()
        var releases = 0
        lateinit var completion: InstallSessionCompletion
        withRetainableOperationLease(release = { releases++ }) {
            completion = bus.registerSession(41)
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        waiter.cancelAndJoin()
        bus.detachSession(completion)
        bus.reset()

        assertEquals(0, releases)
        bus.emitSessionResult(42, completion.token, InstallState.Success)
        bus.emitSessionResult(41, "another attempt", InstallState.Success)
        assertEquals(0, releases)

        bus.emitSessionResult(41, completion.token, InstallState.Success)
        assertEquals(1, releases)
        assertSame(InstallState.Success, completion.await())
        bus.emitSessionResult(41, completion.token, InstallState.Error(UiText.DynamicString("late failure")))
        bus.unregisterSession(completion)
        assertEquals(1, releases)
        assertSame(InstallState.Success, completion.await())
    }

    @Test
    fun `registration retains every nested operation lease until terminal failure`() = runTest {
        val bus = InstallerEventBus()
        var outerReleases = 0
        var innerReleases = 0
        lateinit var completion: InstallSessionCompletion
        withRetainableOperationLease(release = { outerReleases++ }) {
            withRetainableOperationLease(release = { innerReleases++ }) {
                completion = bus.registerSession(41)
            }
            assertEquals(0, innerReleases)
        }
        assertEquals(0, outerReleases)
        assertEquals(0, innerReleases)

        bus.emitSessionResult(41, completion.token, InstallState.Error(UiText.DynamicString("rejected")))

        assertEquals(1, outerReleases)
        assertEquals(1, innerReleases)
    }

    @Test
    fun `unregistering an unsubmitted attempt releases its retained lease exactly once`() = runTest {
        val bus = InstallerEventBus()
        var releases = 0
        lateinit var completion: InstallSessionCompletion
        withRetainableOperationLease(release = { releases++ }) {
            completion = bus.registerSession(41)
        }
        assertEquals(0, releases)

        bus.unregisterSession(completion)
        bus.unregisterSession(completion)
        bus.emitSessionResult(41, completion.token, InstallState.Success)

        assertEquals(1, releases)
    }

    @Test
    fun `background confirmation ends caller waiting without publishing a prompt or releasing ownership`() = runTest {
        val bus = InstallerEventBus()
        var releases = 0
        var prompts = 0
        lateinit var completion: InstallSessionCompletion
        withRetainableOperationLease(release = { releases++ }) {
            completion = bus.registerSession(41, interactive = false)
        }

        bus.emitSessionPendingUserAction(41, completion.token) { prompts++ }

        assertSame(InstallState.UserConfirmationRequired, completion.await())
        assertEquals(0, prompts)
        assertNull(bus.latest)
        assertEquals(0, releases)
        bus.detachSession(completion)
        bus.emitSessionResult(41, completion.token, InstallState.Success)
        assertEquals(1, releases)
    }

    @Test
    fun `pending callbacks require exact active interactive ownership before publishing`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        var prompts = 0
        try {
            for ((sessionId, token) in listOf(41 to null, 42 to completion.token, 41 to "unrelated")) {
                bus.emitSessionPendingUserAction(sessionId, token) { prompts++ }
            }
            assertEquals(0, prompts)
            assertNull(bus.latest)
            bus.emitSessionPendingUserAction(41, completion.token) { prompts++ }
            assertEquals(1, prompts)
            assertSame(InstallState.UserConfirmationRequired, bus.latest)
            assertFalse(waiter.isCompleted)

            bus.detachSession(completion)
            bus.emitSessionPendingUserAction(41, completion.token) { prompts++ }
            assertEquals(1, prompts)
            assertFalse(waiter.isCompleted)

            bus.emitSessionResult(41, completion.token, InstallState.Success)
            assertSame(InstallState.Success, waiter.await())
            bus.emitSessionPendingUserAction(41, completion.token) { prompts++ }
            assertEquals(1, prompts)
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(completion)
        }
    }

    @Test
    fun `detached and duplicate terminal callbacks cannot replace a newer attempts presentation`() = runTest {
        val bus = InstallerEventBus()
        var oldReleases = 0
        lateinit var old: InstallSessionCompletion
        withRetainableOperationLease(release = { oldReleases++ }) {
            old = bus.registerSession(41)
        }
        bus.detachSession(old)
        val current = bus.registerSession(42)
        val currentProgress = InstallState.Installing(0.25f)
        bus.emit(currentProgress)

        bus.emitSessionResult(41, old.token, InstallState.Success)

        assertEquals(1, oldReleases)
        assertSame(currentProgress, bus.latest)
        assertSame(InstallState.Success, old.await())
        bus.emitSessionResult(41, old.token, InstallState.Error(UiText.DynamicString("duplicate failure")))
        bus.emitSessionResult(42, "unknown", InstallState.Success)
        assertSame(currentProgress, bus.latest)

        bus.emitSessionResult(42, current.token, InstallState.Success)
        assertSame(InstallState.Success, current.await())
        assertSame(InstallState.Success, bus.latest)
    }

    @Test
    fun `pending callback cannot replace a terminal result while its ownership is releasing`() = runTest {
        val bus = InstallerEventBus()
        val releaseEntered = CompletableDeferred<Unit>()
        val finishRelease = CompletableDeferred<Unit>()
        lateinit var completion: InstallSessionCompletion
        withRetainableOperationLease(release = {
            releaseEntered.complete(Unit)
            finishRelease.await()
        }) {
            completion = bus.registerSession(41, interactive = false)
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            bus.emitSessionResult(41, completion.token, InstallState.Success)
        }
        try {
            releaseEntered.await()
            bus.emitSessionPendingUserAction(41, completion.token) { error("terminal attempt cannot ask for confirmation") }
            assertFalse(waiter.isCompleted)
        } finally {
            finishRelease.complete(Unit)
            terminal.await()
        }
        assertSame(InstallState.Success, waiter.await())
    }
}
