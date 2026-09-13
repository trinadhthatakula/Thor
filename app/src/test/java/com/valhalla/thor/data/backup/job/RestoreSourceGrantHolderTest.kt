// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreSourceGrantHolderTest {

    @Test
    fun `conditional cleanup drops only an unaccepted generation`() {
        val holder = RestoreSourceGrantHolder()
        val taskId = UUID.randomUUID()
        val token = holder.register(taskId, "content://restore/pending")

        assertTrue(holder.dropIfUnauthorized(taskId, token))
        assertNull(holder.take(taskId, token))
    }

    @Test
    fun `conditional cleanup preserves an authorized generation`() {
        val holder = RestoreSourceGrantHolder()
        val taskId = UUID.randomUUID()
        val token = holder.registerAuthorized(taskId, "content://restore/accepted")

        assertFalse(holder.dropIfUnauthorized(taskId, token))
        assertEquals(token, holder.currentToken(taskId))
        assertEquals("content://restore/accepted", holder.take(taskId, token))
    }

    @Test
    fun `registering another candidate cannot revoke the authorized winner`() {
        val holder = RestoreSourceGrantHolder()
        val taskId = UUID.randomUUID()
        val first = holder.register(taskId, "content://restore/first")

        assertTrue(holder.authorize(taskId, first))

        val second = holder.register(taskId, "content://restore/second")

        assertEquals(first, holder.currentToken(taskId))
        assertFalse(holder.authorize(taskId, second))
        assertTrue(holder.dropIfUnauthorized(taskId, second))
        assertEquals("content://restore/first", holder.take(taskId, first))
    }

    @Test
    fun `multiple pending candidates remain exact until one wins`() {
        val holder = RestoreSourceGrantHolder()
        val taskId = UUID.randomUUID()
        val first = holder.register(taskId, "content://restore/first")
        val second = holder.register(taskId, "content://restore/second")

        assertTrue(holder.authorize(taskId, first))
        assertFalse(holder.authorize(taskId, second))
        assertEquals(first, holder.currentToken(taskId))
        assertFalse(holder.dropIfUnauthorized(taskId, "missing"))
        assertEquals("content://restore/first", holder.take(taskId, first))
        assertNull(holder.take(taskId, second))
    }

    @Test
    fun `dropping a stale winner allows another pending candidate to authorize`() {
        val holder = RestoreSourceGrantHolder()
        val taskId = UUID.randomUUID()
        val first = holder.register(taskId, "content://restore/first")
        val second = holder.register(taskId, "content://restore/second")

        assertTrue(holder.authorize(taskId, first))
        assertTrue(holder.drop(taskId, first))
        assertTrue(holder.authorize(taskId, second))
        assertEquals(second, holder.currentToken(taskId))
        assertEquals("content://restore/second", holder.take(taskId, second))
    }

    @Test
    fun `dropping a task removes every pending candidate`() {
        val holder = RestoreSourceGrantHolder()
        val taskId = UUID.randomUUID()
        val first = holder.register(taskId, "content://restore/first")
        val second = holder.register(taskId, "content://restore/second")

        holder.dropTask(taskId)

        assertFalse(holder.authorize(taskId, first))
        assertFalse(holder.authorize(taskId, second))
        assertNull(holder.currentToken(taskId))
    }
}
