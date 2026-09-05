// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSweepCancellationCoordinatorTest {

    @Test
    fun `active cancellation persists then wakes then interrupts exact owner`() = runTest {
        val events = mutableListOf<String>()
        val decision = PrivilegeSweepCancellationDecision.InterruptActive(REQUEST, 2)
        val coordinator = coordinator(
            request = { events += "persist"; decision },
            wake = { events += "wake"; ServiceStartResult.Requested },
            cancelActive = { events += "interrupt:$it"; true },
            reconcile = { error("live owner settles itself") },
        )

        assertEquals(decision, coordinator.cancel(REQUEST))
        assertEquals(listOf("persist", "wake", "interrupt:$REQUEST"), events)
    }

    @Test
    fun `ownerless active cancellation reconciles stale exact claim after wake rejection`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            request = {
                events += "persist"
                PrivilegeSweepCancellationDecision.InterruptActive(REQUEST, 0)
            },
            wake = {
                events += "wake"
                ServiceStartResult.Rejected(ServiceStartFailure.BACKGROUND_START_NOT_ALLOWED)
            },
            cancelActive = { events += "owner-check"; false },
            reconcile = { events += "reconcile" },
        )

        coordinator.cancel(REQUEST)

        assertEquals(listOf("persist", "wake", "owner-check", "reconcile"), events)
    }

    @Test
    fun `settled cancellation interrupts pre-target child and releases exact owner`() = runTest {
        val owners = PrivilegeSweepOwnerRegistry()
        val child = Job()
        assertTrue(owners.registerProvisional(CLAIM))
        assertTrue(owners.bindRequest(REQUEST, CLAIM))
        assertTrue(owners.attachChild(REQUEST, CLAIM, child))
        child.invokeOnCompletion { owners.unregister(REQUEST, CLAIM) }
        var reconciled = false
        var woke = false
        val coordinator = coordinator(
            request = { PrivilegeSweepCancellationDecision.Settled(REQUEST) },
            wake = { woke = true; ServiceStartResult.AlreadyRunning },
            cancelActive = owners::cancelActive,
            reconcile = { reconciled = true },
        )

        coordinator.cancel(REQUEST)

        assertTrue(woke)
        assertTrue(child.isCancelled)
        assertFalse(owners.isLive(REQUEST, CLAIM))
        assertFalse(reconciled)
    }

    @Test
    fun `already terminal cancellation remains request scoped and only wakes`() = runTest {
        val woken = mutableListOf<UUID>()
        val decision = PrivilegeSweepCancellationDecision.AlreadyTerminal(
            REQUEST,
            PrivilegeSweepRequestState.CANCELLED,
        )
        val coordinator = coordinator(
            request = { decision },
            wake = { woken += it; ServiceStartResult.Requested },
            cancelActive = { error("terminal request cannot own child") },
            reconcile = { error("terminal request cannot have stale claim") },
        )

        assertEquals(decision, coordinator.cancel(REQUEST))
        assertEquals(listOf(REQUEST), woken)
    }

    private fun coordinator(
        request: suspend (UUID) -> PrivilegeSweepCancellationDecision,
        wake: (UUID) -> ServiceStartResult,
        cancelActive: (UUID) -> Boolean,
        reconcile: suspend () -> Unit,
    ) = PrivilegeSweepCancellationCoordinator(
        requestCancellation = request,
        cancelActive = cancelActive,
        wake = wake,
        reconcileStaleClaim = reconcile,
    )

    private companion object {
        val REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        const val CLAIM = "claim-11"
    }
}
