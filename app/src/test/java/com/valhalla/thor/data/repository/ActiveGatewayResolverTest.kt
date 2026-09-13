// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ShellLaneBusy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveGatewayResolverTest {

    @Test
    fun `concurrent cold resolutions share one root probe and cache winner`() = runTest {
        val root = GatedRootProbe()
        val resolver = resolver(root = root::probe)

        val first = async { resolver.resolve(EXECUTION) }
        root.entered.await()
        val second = async { resolver.resolve(EXECUTION) }
        runCurrent()

        assertEquals(1, root.calls)
        root.release.complete(Unit)

        assertEquals(PrivilegeMode.ROOT, first.await().getOrThrow())
        assertEquals(PrivilegeMode.ROOT, second.await().getOrThrow())
        assertEquals(1, root.calls)
    }

    @Test
    fun `direct and active root probes never overlap`() = runTest {
        suspend fun verify(directFirst: Boolean) {
            val root = GatedRootProbe()
            val resolver = resolver(root = root::probe)
            val direct = if (directFirst) {
                async { resolver.isRootAvailable(EXECUTION) }
            } else {
                async { resolver.resolve(EXECUTION).getOrThrow() == PrivilegeMode.ROOT }
            }
            root.entered.await()
            val active = if (directFirst) {
                async { resolver.resolve(EXECUTION).getOrThrow() == PrivilegeMode.ROOT }
            } else {
                async { resolver.isRootAvailable(EXECUTION) }
            }
            runCurrent()

            assertEquals(1, root.calls)
            assertEquals(1, root.maximumConcurrent)
            root.release.complete(Unit)

            assertTrue(direct.await())
            assertTrue(active.await())
            assertEquals(1, root.maximumConcurrent)
        }

        verify(directFirst = true)
        verify(directFirst = false)
    }

    @Test
    fun `cancelling a resolution waiter does not cancel the owner`() = runTest {
        val root = GatedRootProbe()
        val resolver = resolver(root = root::probe)
        val owner = async { resolver.resolve(EXECUTION) }
        root.entered.await()
        val waiter = async { resolver.resolve(EXECUTION) }
        runCurrent()

        waiter.cancelAndJoin()
        root.release.complete(Unit)

        assertEquals(PrivilegeMode.ROOT, owner.await().getOrThrow())
        assertTrue(waiter.isCancelled)
        assertEquals(1, root.calls)
    }

    @Test
    fun `typed and ordinary probe failures are returned and not cached`() = runTest {
        listOf(
            ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE),
            IllegalStateException("root probe failed"),
        ).forEach { failure ->
            var calls = 0
            val resolver = resolver(
                root = {
                    calls++
                    if (calls == 1) throw failure
                    true
                },
            )

            assertSame(failure, resolver.resolve(EXECUTION).exceptionOrNull())
            assertEquals(PrivilegeMode.ROOT, resolver.resolve(EXECUTION).getOrThrow())
            assertEquals(2, calls)
        }
    }

    @Test
    fun `resolution cancellation is rethrown and not cached`() = runTest {
        val cancellation = CancellationException("cancel root probe")
        var calls = 0
        val resolver = resolver(
            root = {
                calls++
                if (calls == 1) throw cancellation
                true
            },
        )

        val caught = runCatching { resolver.resolve(EXECUTION) }.exceptionOrNull()

        assertSame(cancellation, caught)
        assertEquals(PrivilegeMode.ROOT, resolver.resolve(EXECUTION).getOrThrow())
        assertEquals(2, calls)
    }

    @Test
    fun `failed direct probe cannot clear a successful resolution`() = runTest {
        var calls = 0
        var directFailure: Throwable? = null
        val resolver = resolver(
            root = {
                calls++
                if (directFailure != null) throw directFailure!!
                true
            },
        )

        assertEquals(PrivilegeMode.ROOT, resolver.resolve(EXECUTION).getOrThrow())
        directFailure = ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE)
        assertSame(
            directFailure,
            runCatching { resolver.isRootAvailable(EXECUTION) }.exceptionOrNull(),
        )
        assertEquals(PrivilegeMode.ROOT, resolver.resolve(EXECUTION).getOrThrow())
        assertEquals(2, calls)
    }

    @Test
    fun `cache expiry starts after successful resolution`() = runTest {
        var nowMs = 100L
        val root = GatedRootProbe()
        val resolver = resolver(root = root::probe, clockMs = { nowMs })
        val first = async { resolver.resolve(EXECUTION) }
        root.entered.await()

        nowMs = 10_000L
        root.release.complete(Unit)
        assertEquals(PrivilegeMode.ROOT, first.await().getOrThrow())

        nowMs = 12_999L
        assertEquals(PrivilegeMode.ROOT, resolver.resolve(EXECUTION).getOrThrow())
        assertEquals(1, root.calls)

        nowMs = 13_000L
        assertEquals(PrivilegeMode.ROOT, resolver.resolve(EXECUTION).getOrThrow())
        assertEquals(2, root.calls)
    }

    @Test
    fun `preferred gateway and automatic fallback order are preserved`() = runTest {
        val preferredCalls = mutableListOf<String>()
        val preferred = resolver(
            preferred = { PrivilegeMode.SHIZUKU },
            root = { preferredCalls += "root"; true },
            shizuku = { preferredCalls += "shizuku"; false },
            dhizuku = { preferredCalls += "dhizuku"; true },
        )

        assertEquals(PrivilegeMode.ROOT, preferred.resolve(EXECUTION).getOrThrow())
        assertEquals(listOf("shizuku", "root"), preferredCalls)

        val automaticCalls = mutableListOf<String>()
        val automatic = resolver(
            root = { automaticCalls += "root"; false },
            shizuku = { automaticCalls += "shizuku"; false },
            dhizuku = { automaticCalls += "dhizuku"; true },
        )

        assertEquals(PrivilegeMode.DHIZUKU, automatic.resolve(EXECUTION).getOrThrow())
        assertEquals(listOf("root", "shizuku", "dhizuku"), automaticCalls)
    }

    @Test
    fun `no available gateway is a failed result and is not cached`() = runTest {
        var rootCalls = 0
        val resolver = resolver(
            root = { rootCalls++; false },
            shizuku = { false },
            dhizuku = { false },
        )

        assertTrue(resolver.resolve(EXECUTION).isFailure)
        assertTrue(resolver.resolve(EXECUTION).isFailure)
        assertEquals(2, rootCalls)
    }

    private fun resolver(
        preferred: suspend () -> PrivilegeMode? = { null },
        root: suspend (PrivilegeExecutionContext) -> Boolean = { true },
        shizuku: suspend () -> Boolean = { false },
        dhizuku: suspend () -> Boolean = { false },
        clockMs: () -> Long = { 0L },
    ) = ActiveGatewayResolver(
        preferredMode = preferred,
        rootAvailable = root,
        shizukuAvailable = shizuku,
        dhizukuAvailable = dhizuku,
        elapsedRealtimeMs = clockMs,
    )

    private class GatedRootProbe {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
            private set
        var maximumConcurrent = 0
            private set
        private var active = 0

        suspend fun probe(execution: PrivilegeExecutionContext): Boolean {
            assertSame(EXECUTION, execution)
            calls++
            active++
            maximumConcurrent = maxOf(maximumConcurrent, active)
            try {
                if (calls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                return true
            } finally {
                active--
            }
        }
    }

    private companion object {
        val EXECUTION = PrivilegeExecutionContext()
    }
}
