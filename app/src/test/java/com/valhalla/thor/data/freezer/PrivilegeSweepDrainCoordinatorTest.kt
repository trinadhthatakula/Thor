// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.ClaimedPrivilegeSweepRequest
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeSweepDrainCoordinatorTest {

    @Test
    fun `repeated wakes share one FIFO drain and callback is delivered once`() = runTest {
        val events = mutableListOf<String>()
        val claims = ArrayDeque(listOf(claim(1), claim(2)))
        var concurrent = 0
        var maxConcurrent = 0
        val runtime = FakeRuntime(
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            execute = { request, onClaimed ->
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                onClaimed("pkg.${request.queueSequence}")
                events += "run:${request.queueSequence}"
                concurrent--
            },
        )
        val coordinator = coordinator(runtime)

        repeat(20) {
            coordinator.wake(
                onClaimed = { _, packageName -> events += "active:$packageName" },
                onDrained = { events += "drained" },
            )
        }
        advanceUntilIdle()

        assertEquals(1, maxConcurrent)
        assertEquals(listOf("run:1", "run:2"), events.filter { it.startsWith("run:") })
        assertEquals(1, events.count { it == "drained" })
        assertEquals(1, coordinator.drainLaunchCountForTest)
    }

    @Test
    fun `provisional reservation is live before Room claim returns`() = runTest {
        val owners = PrivilegeSweepOwnerRegistry()
        var calls = 0
        val runtime = FakeRuntime(
            claimNext = { _, token ->
                calls++
                if (calls == 1) {
                    assertTrue(owners.isLive(REQUEST_1, token))
                    claim(1).copy(claimToken = token)
                } else {
                    null
                }
            },
            execute = { _, _ -> },
        )

        coordinator(runtime, owners).wake {}
        advanceUntilIdle()

        assertFalse(owners.isLive(REQUEST_1, "claim-1"))
        assertEquals(2, calls)
    }

    @Test
    fun `missing privilege durably blocks while owner remains live and never executes target`() = runTest {
        val owners = PrivilegeSweepOwnerRegistry()
        var calls = 0
        var executed = false
        val runtime = FakeRuntime(
            claimNext = { _, token ->
                calls++
                if (calls == 1) claim(1).copy(claimToken = token) else null
            },
            hasPrivilege = { false },
            block = { request ->
                assertTrue(owners.isLive(request.requestId, request.claimToken))
                true
            },
            execute = { _, _ -> executed = true },
        )

        coordinator(runtime, owners).wake {}
        advanceUntilIdle()

        assertEquals(1, runtime.blockCalls)
        assertFalse(executed)
        assertFalse(owners.isLive(REQUEST_1, runtime.lastClaimToken))
    }

    @Test
    fun `one request failure does not strand later FIFO request`() = runTest {
        val claims = ArrayDeque(listOf(claim(1), claim(2)))
        val executions = mutableListOf<Long>()
        val runtime = FakeRuntime(
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            execute = { request, _ ->
                executions += request.queueSequence
                if (request.queueSequence == 1L) error("settled request failure")
            },
        )

        coordinator(runtime).wake {}
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), executions)
    }

    @Test
    fun `enqueue during final empty arbitration retains latest wake and drains it`() = runTest {
        val events = mutableListOf<String>()
        var claimCalls = 0
        var emptyChecks = 0
        lateinit var coordinator: PrivilegeSweepDrainCoordinator
        val runtime = FakeRuntime(
            claimNext = { _, token ->
                claimCalls++
                if (claimCalls == 2) claim(2).copy(claimToken = token) else null
            },
            execute = { request, _ -> events += "run:${request.queueSequence}" },
            finish = { onEmpty ->
                emptyChecks++
                if (emptyChecks == 1) {
                    coordinator.wake { events += "latest-drained" }
                    false
                } else {
                    onEmpty()
                    true
                }
            },
        )
        coordinator = coordinator(runtime)

        coordinator.wake { events += "first-drained" }
        advanceUntilIdle()

        assertEquals(listOf("run:2", "latest-drained"), events)
        assertEquals(1, coordinator.drainLaunchCountForTest)
    }

    @Test
    fun `infrastructure failure is never reported as an empty drain`() = runTest {
        for (boundary in listOf("cutover", "recover", "claim", "empty")) {
            var drained = 0
            val runtime = FakeRuntime(
                claimNext = { _, _ -> if (boundary == "claim") error("claim") else null },
                execute = { _, _ -> error("must not execute") },
                cutover = { if (boundary == "cutover") error("cutover") },
                recover = { if (boundary == "recover") error("recover") },
                finish = { if (boundary == "empty") error("empty") else { it(); true } },
            )
            coordinator(runtime).wake { drained++ }
            advanceUntilIdle()
            assertEquals(boundary, 0, drained)
        }
    }

    @Test
    fun `shutdown cancels execution but holds lane and owner through cleanup and settlement`() = runTest {
        val owners = PrivilegeSweepOwnerRegistry()
        val cleanup = CompletableDeferred<Unit>()
        val settlement = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = false
        var callbacks = 0
        val runtime = FakeRuntime(
            claimNext = { _, token -> calls++; claim(1).copy(claimToken = token) },
            execute = { _, _ ->
                try { awaitCancellation() } finally {
                    cancelled = true
                    withContext(NonCancellable) { cleanup.await() }
                }
            },
            settle = { request ->
                assertTrue(owners.isLive(request.requestId, request.claimToken))
                settlement.await()
                true
            },
        )
        val coordinator = coordinator(runtime, owners)
        coordinator.wake { callbacks++ }
        runCurrent()
        coordinator.shutdown()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(owners.isLive(REQUEST_1, "claim-1"))
        var otherClaims = 0
        val other = coordinator(FakeRuntime(
            claimNext = { _, _ -> otherClaims++; null }, execute = { _, _ -> },
        ), owners)
        other.wake {}
        runCurrent()
        assertEquals(0, otherClaims)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(owners.isLive(REQUEST_1, "claim-1"))
        assertEquals(0, otherClaims)
        settlement.complete(Unit)
        advanceUntilIdle()
        coordinator.wake { callbacks++ }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(1, otherClaims)
        assertEquals(0, callbacks)
        assertFalse(owners.isLive(REQUEST_1, "claim-1"))
        other.shutdown()
    }

    @Test
    fun `shutdown while waiting privilege settles and never executes`() = runTest {
        var settlements = 0
        var calls = 0
        val runtime = FakeRuntime(
            claimNext = { _, token -> calls++; claim(1).copy(claimToken = token) },
            hasPrivilege = { awaitCancellation() },
            execute = { _, _ -> error("must not execute") },
            settle = { settlements++; true },
        )
        val coordinator = coordinator(runtime)
        coordinator.wake { error("destroyed callback") }
        runCurrent()
        coordinator.shutdown()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(1, settlements)
    }

    @Test
    fun `shutdown while waiting for another generation never claims or releases its lane`() = runTest {
        val owners = PrivilegeSweepOwnerRegistry()
        owners.acquireLane("earlier-generation")
        val coordinator = coordinator(FakeRuntime(
            claimNext = { _, _ -> error("must not claim") }, execute = { _, _ -> },
        ), owners)
        coordinator.wake { error("must not callback") }
        runCurrent()
        coordinator.shutdown()
        advanceUntilIdle()
        owners.releaseLane("earlier-generation")
        advanceUntilIdle()
        assertEquals(1, coordinator.drainLaunchCountForTest)
    }

    @Test
    fun `cancellation before lazy child attachment still settles exact owner`() = runTest {
        val owners = PrivilegeSweepOwnerRegistry()
        var calls = 0
        var settled = 0
        val runtime = FakeRuntime(
            claimNext = { _, token ->
                if (++calls == 1) {
                    owners.cancelActive(REQUEST_1)
                    claim(1).copy(claimToken = token)
                } else null
            },
            execute = { _, _ -> error("cancelled child must not execute") },
            settle = { request ->
                assertTrue(owners.isLive(request.requestId, request.claimToken))
                settled++; true
            },
        )
        coordinator(runtime, owners).wake {}
        advanceUntilIdle()
        assertEquals(1, settled)
        assertFalse(owners.isLive(REQUEST_1, "claim-1"))
    }

    @Test
    fun `failed privilege block is settled rather than abandoned`() = runTest {
        for (throws in listOf(false, true)) {
            var calls = 0
            var settlements = 0
            val runtime = FakeRuntime(
                claimNext = { _, token -> if (++calls == 1) claim(1).copy(claimToken = token) else null },
                hasPrivilege = { false },
                block = { if (throws) error("storage") else false },
                execute = { _, _ -> error("must not execute") },
                settle = { settlements++; true },
            )
            coordinator(runtime).wake {}
            advanceUntilIdle()
            assertEquals(1, settlements)
        }
    }

    @Test
    fun `uncommitted settlement aborts without clean drain or automatic retry`() = runTest {
        for (timeout in listOf(false, true)) {
            var claims = 0
            var aborts = 0
            var clean = 0
            val runtime = FakeRuntime(
                claimNext = { _, token -> claims++; claim(1).copy(claimToken = token) },
                execute = { _, _ -> error("storage") },
                settle = { if (timeout) awaitCancellation() else false },
            )
            val coordinator = coordinator(runtime)
            coordinator.wake(onAborted = { aborts++ }) { clean++ }
            advanceUntilIdle()
            assertEquals(1, claims)
            assertEquals(1, aborts)
            assertEquals(1, runtime.abortCalls)
            assertEquals(0, clean)
            coordinator.shutdown()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        runtime: PrivilegeSweepDrainRuntime,
        owners: PrivilegeSweepOwnerRegistry = PrivilegeSweepOwnerRegistry(),
    ) = PrivilegeSweepDrainCoordinator(
        dispatcher = StandardTestDispatcher(testScheduler),
        owners = owners,
        runtime = runtime,
        sessionToken = "session-11",
        claimTokenFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "claim-${++next}"
        },
    )

    private class FakeRuntime(
        private val claimNext: suspend (String, String) -> ClaimedPrivilegeSweepRequest?,
        private val hasPrivilege: suspend () -> Boolean = { true },
        private val block: suspend (ClaimedPrivilegeSweepRequest) -> Boolean = { true },
        private val execute: suspend (ClaimedPrivilegeSweepRequest, (String) -> Unit) -> Unit,
        private val finish: suspend (() -> Unit) -> Boolean = { onEmpty -> onEmpty(); true },
        private val cutover: suspend () -> Unit = {},
        private val recover: suspend () -> Unit = {},
        private val settle: suspend (ClaimedPrivilegeSweepRequest) -> Boolean = { true },
    ) : PrivilegeSweepDrainRuntime {
        var abortCalls = 0
        var blockCalls = 0
        var lastClaimToken = ""

        override suspend fun awaitCutover() = cutover()
        override suspend fun recoverClaims(
            sessionToken: String,
            localOwnerIsLive: (UUID, String) -> Boolean,
        ) = recover()

        override suspend fun claimNext(
            sessionToken: String,
            claimToken: String,
        ): ClaimedPrivilegeSweepRequest? {
            lastClaimToken = claimToken
            return claimNext.invoke(sessionToken, claimToken)
        }

        override suspend fun hasPrivilege(): Boolean = hasPrivilege.invoke()

        override suspend fun blockMissingPrivilege(claim: ClaimedPrivilegeSweepRequest): Boolean {
            blockCalls++
            return block(claim)
        }

        override suspend fun executeClaim(
            claim: ClaimedPrivilegeSweepRequest,
            onTargetClaimed: (String) -> Unit,
        ) = execute(claim, onTargetClaimed)

        override suspend fun settleClaim(claim: ClaimedPrivilegeSweepRequest) = settle(claim)
        override suspend fun abortDrain() { abortCalls++ }

        override suspend fun finishDrainIfEmpty(onQueueEmpty: () -> Unit): Boolean = finish(onQueueEmpty)
    }

    private fun claim(number: Int) = ClaimedPrivilegeSweepRequest(
        requestId = if (number == 1) REQUEST_1 else REQUEST_2,
        queueSequence = number.toLong(),
        payloadSchemaVersion = 1,
        executionId = if (number == 1) REQUEST_1 else REQUEST_2,
        operation = PrivilegeSweepOperation.CLEAR_CACHE,
        freezerMode = null,
        userId = 0,
        source = PrivilegeSweepSource.MAIN,
        sourceAssociations = setOf(PrivilegeSweepSource.MAIN.name),
        targetCount = 1,
        succeeded = 0,
        failed = 0,
        busy = 0,
        unresolved = 1,
        serviceSessionToken = "session-11",
        claimToken = "placeholder",
        claimLeaseExpiresAtEpochMs = 11_000L,
        attemptCount = 1,
        createdAtEpochMs = number.toLong(),
        claimedAtEpochMs = 1_000L,
    )

    private companion object {
        val REQUEST_1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val REQUEST_2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
    }
}
