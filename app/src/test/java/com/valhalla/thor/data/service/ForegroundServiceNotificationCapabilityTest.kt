// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ForegroundServiceNotificationCapabilityTest {

    @Test
    fun `available notification surface promotes and permits execution`() {
        val capability = capability()
        var promoted = false

        val state = capability.evaluateAndPromote(DATA_FOREGROUND_CHANNEL_ID) {
            promoted = true
        }

        assertSame(ForegroundNotificationState.Available, state)
        assertTrue(state.permitsExecution)
        assertTrue(promoted)
    }

    @Test
    fun `API 33 denied post notifications still promotes and permits execution`() {
        val capability = capability(sdkInt = 33, postNotificationsGranted = false)
        var promoted = false

        val state = capability.evaluateAndPromote(DATA_FOREGROUND_CHANNEL_ID) {
            promoted = true
        }

        assertSame(ForegroundNotificationState.PostPermissionDenied, state)
        assertTrue(state.permitsExecution)
        assertTrue(promoted)
    }

    @Test
    fun `blocked channel prevents a new foreground execution`() {
        val capability = capability(channelImportance = NotificationManager.IMPORTANCE_NONE)
        var promoted = false

        val state = capability.evaluateAndPromote(DATA_FOREGROUND_CHANNEL_ID) {
            promoted = true
        }

        assertSame(ForegroundNotificationState.Blocked, state)
        assertFalse(state.permitsExecution)
        assertFalse(promoted)
    }

    @Test
    fun `app notification block prevents a new foreground execution`() {
        val capability = capability(appNotificationsEnabled = false)
        var promoted = false

        val state = capability.evaluateAndPromote(DATA_FOREGROUND_CHANNEL_ID) {
            promoted = true
        }

        assertSame(ForegroundNotificationState.Blocked, state)
        assertFalse(promoted)
    }

    @Test
    fun `missing channel is invalid and aborts before promotion`() {
        val capability = capability(channelImportance = null)
        var promoted = false

        val state = capability.evaluateAndPromote(DATA_FOREGROUND_CHANNEL_ID) {
            promoted = true
        }

        assertTrue(state is ForegroundNotificationState.Invalid)
        assertFalse(state.permitsExecution)
        assertFalse(promoted)
    }

    @Test
    fun `notification construction failure is invalid and aborts execution`() {
        val state = capability().evaluateAndPromote(DATA_FOREGROUND_CHANNEL_ID) {
            throw IllegalArgumentException("invalid notification")
        }

        assertEquals(
            ForegroundNotificationState.Invalid("invalid notification"),
            state,
        )
        assertFalse(state.permitsExecution)
    }

    @Test
    fun `foreground promotion failure is invalid and aborts execution`() {
        val state = capability().evaluateAndPromote(DATA_FOREGROUND_CHANNEL_ID) {
            throw SecurityException("promotion rejected")
        }

        assertEquals(
            ForegroundNotificationState.Invalid("promotion rejected"),
            state,
        )
        assertFalse(state.permitsExecution)
    }

    @Test
    fun `service notification identifiers are stable separated and immutable`() {
        assertEquals("thor.jobs.data", DATA_FOREGROUND_CHANNEL_ID)
        assertEquals("thor.jobs.privileged", PRIVILEGED_FOREGROUND_CHANNEL_ID)
        assertEquals(0x0c00_0000, FOREGROUND_PENDING_INTENT_FLAGS)

        val taskId = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val otherTaskId = UUID.fromString("87654321-4321-8765-cba9-876543210fed")
        val requestCodes = ForegroundPendingIntentNamespace.entries.map { namespace ->
            namespace.requestCode(taskId)
        }

        assertEquals(requestCodes.size, requestCodes.toSet().size)
        assertEquals(
            ForegroundPendingIntentNamespace.DATA_CONTENT.requestCode(taskId),
            ForegroundPendingIntentNamespace.DATA_CONTENT.requestCode(taskId),
        )
        assertNotEquals(
            ForegroundPendingIntentNamespace.DATA_CONTENT.requestCode(taskId),
            ForegroundPendingIntentNamespace.DATA_CONTENT.requestCode(otherTaskId),
        )
    }

    private fun capability(
        sdkInt: Int = 32,
        postNotificationsGranted: Boolean = true,
        appNotificationsEnabled: Boolean = true,
        channelImportance: Int? = NotificationManager.IMPORTANCE_LOW,
    ) = ForegroundServiceNotificationCapability(
        sdkInt = sdkInt,
        postNotificationsGranted = { postNotificationsGranted },
        appNotificationsEnabled = { appNotificationsEnabled },
        channelImportance = { channelImportance },
    )
}
