// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataTaskOwnerRegistryTest {

    @Test
    fun `provisional claim token is live across Room claim to child start window`() {
        val registry = DataTaskOwnerRegistry()

        registry.registerProvisional(CLAIM)

        assertTrue(registry.isLive(TASK, CLAIM))
        assertTrue(registry.bindTask(TASK, CLAIM))
        assertTrue(registry.isLive(TASK, CLAIM))
    }

    @Test
    fun `only exact task and claim owner can unregister`() {
        val registry = DataTaskOwnerRegistry()
        registry.registerProvisional(CLAIM)
        registry.bindTask(TASK, CLAIM)

        registry.unregister(OTHER_TASK, CLAIM)
        registry.unregister(TASK, OTHER_CLAIM)
        assertTrue(registry.isLive(TASK, CLAIM))

        registry.unregister(TASK, CLAIM)
        assertFalse(registry.isLive(TASK, CLAIM))
    }

    @Test
    fun `matching cancellation interrupts only the matching child`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val matchingStarted = CompletableDeferred<Unit>()
        val otherStarted = CompletableDeferred<Unit>()
        val matching = launch {
            matchingStarted.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val other = launch {
            otherStarted.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        matchingStarted.await()
        otherStarted.await()
        registry.registerProvisional(CLAIM)
        registry.bindTask(TASK, CLAIM)
        registry.attachChild(TASK, CLAIM, matching)
        registry.registerProvisional(OTHER_CLAIM)
        registry.bindTask(OTHER_TASK, OTHER_CLAIM)
        registry.attachChild(OTHER_TASK, OTHER_CLAIM, other)

        assertTrue(registry.cancelActive(TASK))
        matching.join()

        assertTrue(matching.isCancelled)
        assertTrue(other.isActive)
        other.cancel()
    }

    @Test
    fun `cancellation during provisional claim is retained through task binding`() {
        val registry = DataTaskOwnerRegistry()
        val child = Job()
        registry.registerProvisional(CLAIM)

        assertTrue(registry.cancelActive(TASK))
        assertTrue(registry.bindTask(TASK, CLAIM))
        assertTrue(registry.attachChild(TASK, CLAIM, child))

        assertTrue(child.isCancelled)
    }

    @Test
    fun `cancellation requested before child attachment cancels that child on attachment`() {
        val registry = DataTaskOwnerRegistry()
        val child = Job()
        registry.registerProvisional(CLAIM)
        registry.bindTask(TASK, CLAIM)

        assertTrue(registry.cancelActiveOwnedBy(CLAIM))
        assertTrue(registry.attachChild(TASK, CLAIM, child))

        assertTrue(child.isCancelled)
        assertTrue(registry.isLive(TASK, CLAIM))
    }

    @Test
    fun `cancelled child remains a live local owner until settlement unregisters it`() {
        val registry = DataTaskOwnerRegistry()
        val child = Job().also(Job::cancel)
        registry.registerProvisional(CLAIM)
        registry.bindTask(TASK, CLAIM)
        registry.attachChild(TASK, CLAIM, child)

        assertTrue(registry.isLive(TASK, CLAIM))
        registry.unregister(TASK, CLAIM)
        assertFalse(registry.isLive(TASK, CLAIM))
    }

    private companion object {
        val TASK: UUID = UUID.fromString("00000000-0000-0000-0000-000000000091")
        val OTHER_TASK: UUID = UUID.fromString("00000000-0000-0000-0000-000000000092")
        const val CLAIM = "claim-91"
        const val OTHER_CLAIM = "claim-92"
    }
}
