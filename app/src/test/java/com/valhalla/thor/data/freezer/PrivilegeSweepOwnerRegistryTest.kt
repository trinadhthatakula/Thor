// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSweepOwnerRegistryTest {

    @Test
    fun `provisional token is live before request identity is bound`() {
        val registry = PrivilegeSweepOwnerRegistry()

        assertTrue(registry.registerProvisional(CLAIM))
        assertTrue(registry.isLive(REQUEST, CLAIM))
        assertTrue(registry.bindRequest(REQUEST, CLAIM))
        assertTrue(registry.isLive(REQUEST, CLAIM))
    }

    @Test
    fun `queue-wide owner rejects a second provisional claim`() {
        val registry = PrivilegeSweepOwnerRegistry()

        assertTrue(registry.registerProvisional(CLAIM))
        assertFalse(registry.registerProvisional(OTHER_CLAIM))
        registry.unregisterProvisional(CLAIM)
        assertTrue(registry.registerProvisional(OTHER_CLAIM))
    }

    @Test
    fun `only exact request and claim can unregister bound owner`() {
        val registry = PrivilegeSweepOwnerRegistry()
        registry.registerProvisional(CLAIM)
        registry.bindRequest(REQUEST, CLAIM)

        registry.unregister(OTHER_REQUEST, CLAIM)
        registry.unregister(REQUEST, OTHER_CLAIM)
        assertTrue(registry.isLive(REQUEST, CLAIM))

        registry.unregister(REQUEST, CLAIM)
        assertFalse(registry.isLive(REQUEST, CLAIM))
    }

    @Test
    fun `cancellation before request and child attachment is retained`() {
        val registry = PrivilegeSweepOwnerRegistry()
        val child = Job()
        registry.registerProvisional(CLAIM)

        assertFalse(registry.cancelActive(REQUEST))
        assertTrue(registry.bindRequest(REQUEST, CLAIM))
        assertTrue(registry.attachChild(REQUEST, CLAIM, child))

        assertTrue(child.isCancelled)
        assertTrue(registry.isLive(REQUEST, CLAIM))
    }

    @Test
    fun `matching cancellation interrupts only exact bound child`() {
        val registry = PrivilegeSweepOwnerRegistry()
        val child = Job()
        registry.registerProvisional(CLAIM)
        registry.bindRequest(REQUEST, CLAIM)
        registry.attachChild(REQUEST, CLAIM, child)

        assertFalse(registry.cancelActive(OTHER_REQUEST))
        assertFalse(child.isCancelled)
        assertTrue(registry.cancelActive(REQUEST))
        assertTrue(child.isCancelled)
    }

    @Test
    fun `replacement generation waits until queue lane is released`() = runTest {
        val registry = PrivilegeSweepOwnerRegistry()
        registry.acquireLane("generation-1")
        var acquired = false
        val replacement = launch {
            registry.acquireLane("generation-2")
            acquired = true
        }

        runCurrent()
        assertFalse(acquired)
        registry.releaseLane("wrong-generation")
        runCurrent()
        assertFalse(acquired)

        registry.releaseLane("generation-1")
        replacement.join()
        assertTrue(acquired)
        registry.releaseLane("generation-2")
    }

    private companion object {
        val REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val OTHER_REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
        const val CLAIM = "claim-11"
        const val OTHER_CLAIM = "claim-12"
    }
}
