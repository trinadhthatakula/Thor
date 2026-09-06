// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExternalResultMailboxTest {

    @Test
    fun `completion is replayed after listener gap until acknowledged`() {
        val mailbox = TaskExternalResultMailbox<String>()
        var deliveries = 0
        val listener = { deliveries += 1 }

        mailbox.complete("task:7")
        mailbox.addListener("task:7", listener)
        mailbox.removeListener("task:7", listener)
        mailbox.addListener("task:7", listener)

        assertEquals(2, deliveries)
        assertTrue(mailbox.acknowledge("task:7"))
        mailbox.removeListener("task:7", listener)
        mailbox.addListener("task:7", listener)
        assertEquals(2, deliveries)
        assertFalse(mailbox.acknowledge("task:7"))
    }

    @Test
    fun `shizuku requests get collision free codes and reuse only the same owner`() {
        val registry = TaskShizukuRequestRegistry<String>(firstRequestCode = 100)

        val first = registry.register("owner-a") { code -> "listener-$code" }
        val duplicate = registry.register("owner-a") { code -> "duplicate-$code" }
        val second = registry.register("owner-b") { code -> "listener-$code" }

        assertTrue(first.inserted)
        assertFalse(duplicate.inserted)
        assertEquals(first.requestCode, duplicate.requestCode)
        assertEquals(first.listener, duplicate.listener)
        assertNotEquals(first.requestCode, second.requestCode)
        assertTrue(second.inserted)
    }

    @Test
    fun `binder death drains every owner and rejects stale removal`() {
        val abandoned = mutableListOf<String>()
        val removedListeners = mutableListOf<String>()
        val registry = TaskShizukuRequestRegistry(
            firstRequestCode = 100,
            onAbandoned = abandoned::add,
        ) { listener: String -> removedListeners += listener }
        val first = registry.register("owner-a") { code -> "listener-$code" }
        registry.register("owner-b") { code -> "listener-$code" }

        registry.abandonAll()

        assertEquals(listOf("owner-a", "owner-b"), abandoned)
        assertEquals(listOf("listener-100", "listener-101"), removedListeners)
        assertNull(registry.remove("owner-a", first.requestCode))
        assertTrue(registry.register("owner-a") { "replacement-$it" }.inserted)
    }

    @Test
    fun `stale listener removal cannot detach replacement owner`() {
        val mailbox = TaskExternalResultMailbox<String>()
        var staleDeliveries = 0
        var currentDeliveries = 0
        val stale = { staleDeliveries += 1 }
        val current = { currentDeliveries += 1 }

        mailbox.addListener("task:7", stale)
        mailbox.addListener("task:7", current)
        mailbox.removeListener("task:7", stale)
        mailbox.complete("task:7")

        assertEquals(0, staleDeliveries)
        assertEquals(1, currentDeliveries)
    }

    @Test
    fun `discard removes only the superseded mailbox owner`() {
        val mailbox = TaskExternalResultMailbox<String>()
        var staleDeliveries = 0
        var currentDeliveries = 0

        mailbox.complete("stale")
        mailbox.complete("current")
        mailbox.discard("stale")
        mailbox.addListener("stale") { staleDeliveries += 1 }
        mailbox.addListener("current") { currentDeliveries += 1 }

        assertEquals(0, staleDeliveries)
        assertEquals(1, currentDeliveries)
    }

    @Test
    fun `retiring a shizuku owner removes its listener without publishing a result`() {
        val abandoned = mutableListOf<String>()
        val removedListeners = mutableListOf<String>()
        val registry = TaskShizukuRequestRegistry(
            firstRequestCode = 100,
            onAbandoned = abandoned::add,
        ) { listener: String -> removedListeners += listener }
        val stale = registry.register("owner-a") { code -> "listener-$code" }

        assertTrue(registry.retire("owner-a"))
        assertEquals(listOf(stale.listener), removedListeners)
        assertTrue(abandoned.isEmpty())

        val replacement = registry.register("owner-a") { code -> "replacement-$code" }
        assertTrue(replacement.inserted)
        assertNull(registry.remove("owner-a", stale.requestCode))
    }

    @Test
    fun `dhizuku attempt remains a deduplication barrier until acknowledged`() {
        val registry = TaskDhizukuRequestRegistry()

        val first = registry.register("owner-a")
        assertTrue(first.inserted)
        assertTrue(registry.markIssued("owner-a", first.generation))
        assertFalse(registry.register("owner-a").inserted)

        assertTrue(registry.complete("owner-a", first.generation))
        assertFalse(registry.register("owner-a").inserted)
        assertTrue(registry.acknowledge("owner-a"))

        val replacement = registry.register("owner-a")
        assertTrue(replacement.inserted)
        assertNotEquals(first.generation, replacement.generation)
    }

    @Test
    fun `stale dhizuku attempt cannot mutate its replacement`() {
        val registry = TaskDhizukuRequestRegistry()
        val stale = registry.register("owner-a")
        assertTrue(registry.removeIfStarting("owner-a", stale.generation))
        val replacement = registry.register("owner-a")

        assertFalse(registry.markIssued("owner-a", stale.generation))
        assertFalse(registry.complete("owner-a", stale.generation))
        assertFalse(registry.removeIfStarting("owner-a", stale.generation))
        assertFalse(registry.acknowledge("owner-a"))

        assertTrue(registry.markIssued("owner-a", replacement.generation))
        assertTrue(registry.complete("owner-a", replacement.generation))
        assertTrue(registry.acknowledge("owner-a"))
        assertFalse(registry.complete("owner-a", replacement.generation))
    }

    @Test
    fun `retired dhizuku attempt rejects delayed callbacks and permits replacement`() {
        val registry = TaskDhizukuRequestRegistry()
        val stale = registry.register("owner-a")
        assertTrue(registry.markIssued("owner-a", stale.generation))

        assertTrue(registry.retire("owner-a"))
        val replacement = registry.register("owner-a")

        assertFalse(registry.complete("owner-a", stale.generation))
        assertTrue(registry.markIssued("owner-a", replacement.generation))
        assertTrue(registry.complete("owner-a", replacement.generation))
    }

}
