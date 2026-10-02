// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain

import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
