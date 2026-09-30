// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootAvailabilityState
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.RootAvailabilityProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveGatewayResolverTest {
    private class Root(initial: RootAvailabilityState = ready(RootConfirmation.ROOT)) : RootAvailabilityProvider {
        override val state = MutableStateFlow(initial)
        var initialWaits = 0
        override suspend fun awaitInitialObservation(): RootAvailabilityState {
            initialWaits++
            return state.value
        }
    }

    @Test
    fun `same-value root refresh invalidates a measured alternative route`() = runTest {
        val root = Root(ready(RootConfirmation.NON_ROOT))
        var calls = 0
        val resolver = resolver(root, shizuku = { calls++; true })
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        assertEquals(1, calls)

        root.state.value = ready(RootConfirmation.NON_ROOT, revision = 2)
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        assertEquals(2, calls)
    }

    @Test
    fun `changing preference takes effect before cache expiry`() = runTest {
        var preferred: PrivilegeMode? = null
        val resolver = resolver(preferred = { preferred }, shizuku = { true }, dhizuku = { true })
        assertEquals(PrivilegeMode.ROOT, resolver.resolve().getOrThrow())
        preferred = PrivilegeMode.SHIZUKU
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        preferred = PrivilegeMode.DHIZUKU
        assertEquals(PrivilegeMode.DHIZUKU, resolver.resolve().getOrThrow())
        preferred = null
        assertEquals(PrivilegeMode.ROOT, resolver.resolve().getOrThrow())
    }

    @Test
    fun `unresolved root freshness preserves auto route without claiming confirmed root`() = runTest {
        for (status in listOf(RootRefreshStatus.CHECKING, RootRefreshStatus.BUSY, RootRefreshStatus.TIMED_OUT, RootRefreshStatus.FAILED)) {
            for (confirmation in listOf(RootConfirmation.UNKNOWN, RootConfirmation.ROOT)) {
                val root = Root(RootAvailabilityState(confirmation, status, hasCompletedRefresh = true))
                val resolver = resolver(root, shizuku = { error("automatic fallback during unresolved root") })
                assertEquals(PrivilegeMode.ROOT, resolver.resolve().getOrThrow())
                assertEquals(confirmation == RootConfirmation.ROOT, resolver.isRootAvailable())
                assertTrue(resolver.canSelectRoot())
            }
        }
    }

    @Test
    fun `automatic Shizuku survives unresolved refresh until root is confirmed`() = runTest {
        val root = Root(ready(RootConfirmation.NON_ROOT))
        val resolver = resolver(root, shizuku = { true })
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        assertFalse(resolver.canSelectRoot())

        for (status in listOf(RootRefreshStatus.CHECKING, RootRefreshStatus.BUSY, RootRefreshStatus.TIMED_OUT, RootRefreshStatus.FAILED)) {
            root.state.value = root.state.value.copy(refreshStatus = status, revision = root.state.value.revision + 1)
            assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
            assertFalse(resolver.isRootAvailable())
            assertTrue("Root-only actions still need a typed admission refusal", resolver.canSelectRoot())
        }

        root.state.value = ready(RootConfirmation.ROOT, revision = root.state.value.revision + 1)
        assertEquals(PrivilegeMode.ROOT, resolver.resolve().getOrThrow())
        assertTrue(resolver.isRootAvailable())
        assertTrue(resolver.canSelectRoot())
    }

    @Test
    fun `explicit independent provider remains usable during root failure`() = runTest {
        val root = Root(RootAvailabilityState(refreshStatus = RootRefreshStatus.FAILED, hasCompletedRefresh = true))
        assertEquals(PrivilegeMode.SHIZUKU, resolver(root, preferred = { PrivilegeMode.SHIZUKU }, shizuku = { true }).resolve().getOrThrow())
        assertEquals(PrivilegeMode.DHIZUKU, resolver(root, preferred = { PrivilegeMode.DHIZUKU }, dhizuku = { true }).resolve().getOrThrow())
    }

    @Test
    fun `explicit alternative does not start or wait for initial root acquisition`() = runTest {
        val root = Root(RootAvailabilityState())
        val resolver = resolver(root, preferred = { PrivilegeMode.SHIZUKU }, shizuku = { true })
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        assertEquals(0, root.initialWaits)
    }

    @Test
    fun `confirmed non-root follows automatic provider order`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(Root(ready(RootConfirmation.NON_ROOT)),
            shizuku = { calls += "shizuku"; false }, dhizuku = { calls += "dhizuku"; true })
        assertEquals(PrivilegeMode.DHIZUKU, resolver.resolve().getOrThrow())
        assertEquals(listOf("shizuku", "dhizuku"), calls)
        assertFalse(resolver.canSelectRoot())
    }

    @Test
    fun `a suspended old revision cannot return or cache its provider choice`() = runTest {
        val root = Root(ready(RootConfirmation.NON_ROOT))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = resolver(root, shizuku = { entered.complete(Unit); release.await(); true })
        val resolving = async { resolver.resolve() }
        entered.await()
        root.state.value = ready(RootConfirmation.ROOT, revision = 2)
        release.complete(Unit)
        assertEquals(PrivilegeMode.ROOT, resolving.await().getOrThrow())
        assertEquals(PrivilegeMode.ROOT, resolver.resolve().getOrThrow())
    }

    @Test
    fun `a suspended old preference cannot return or cache its provider choice`() = runTest {
        var preferred = PrivilegeMode.SHIZUKU
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = resolver(preferred = { preferred }, shizuku = { entered.complete(Unit); release.await(); true }, dhizuku = { true })
        val resolving = async { resolver.resolve() }
        entered.await()
        preferred = PrivilegeMode.DHIZUKU
        release.complete(Unit)
        assertEquals(PrivilegeMode.DHIZUKU, resolving.await().getOrThrow())
    }

    @Test
    fun `provider failure is not cached and cancellation propagates`() = runTest {
        for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            var calls = 0
            val resolver = resolver(preferred = { PrivilegeMode.SHIZUKU }, shizuku = {
                if (calls++ == 0) throw failure
                true
            })
            val caught = runCatching { resolver.resolve().getOrThrow() }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        }
    }

    @Test
    fun `alternative availability still expires within a revision`() = runTest {
        var now = 0L
        var available = true
        val resolver = resolver(preferred = { PrivilegeMode.SHIZUKU }, shizuku = { available }, clock = { now })
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
        available = false
        now = 3_000L
        assertEquals(PrivilegeMode.ROOT, resolver.resolve().getOrThrow())
    }

    @Test
    fun `no available gateway is not cached`() = runTest {
        var available = false
        val resolver = resolver(Root(ready(RootConfirmation.NON_ROOT)), shizuku = { available })
        assertTrue(resolver.resolve().isFailure)
        available = true
        assertEquals(PrivilegeMode.SHIZUKU, resolver.resolve().getOrThrow())
    }

    private fun resolver(
        root: Root = Root(),
        preferred: suspend () -> PrivilegeMode? = { null },
        shizuku: suspend () -> Boolean = { false },
        dhizuku: suspend () -> Boolean = { false },
        clock: () -> Long = { 0L },
    ) = ActiveGatewayResolver(preferred, root, shizuku, dhizuku, clock)

    private companion object {
        fun ready(confirmation: RootConfirmation, revision: Long = 1) = RootAvailabilityState(
            confirmation = confirmation, revision = revision, confirmedRevision = revision, hasCompletedRefresh = true,
        )
    }
}
