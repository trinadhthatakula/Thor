// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.HomeActivity
import com.valhalla.thor.presentation.launcher.TaskQueueLaunchActivity
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PrivilegeSweepServiceNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = PrivilegeSweepServiceNotification(context)

    @Test
    fun `running notification has an immutable UUID-specific task deep link`() {
        val requestId = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val otherRequestId = UUID.fromString("87654321-4321-8765-cba9-876543210fed")

        val contentIntent =
            requireNotNull(notifications.running(requestId, "com.example.app").contentIntent)
        val savedIntent = shadowOf(contentIntent).savedIntent
        val otherContentIntent =
            requireNotNull(notifications.running(otherRequestId, "com.example.other").contentIntent)

        assertEquals(
            ComponentName(context, TaskQueueLaunchActivity::class.java),
            savedIntent.component,
        )
        assertEquals(
            setOf(TaskQueueLaunchActivity.EXTRA_TASK_ID),
            savedIntent.extras?.keySet(),
        )
        assertEquals(
            requestId.toString(),
            savedIntent.getStringExtra(TaskQueueLaunchActivity.EXTRA_TASK_ID),
        )
        assertTrue(shadowOf(contentIntent).isImmutable)
        assertNotEquals(contentIntent, otherContentIntent)
    }

    @Test
    fun `preparing notification keeps the generic app target`() {
        val contentIntent = requireNotNull(notifications.preparing().contentIntent)
        val savedIntent = shadowOf(contentIntent).savedIntent

        assertEquals(ComponentName(context, HomeActivity::class.java), savedIntent.component)
        assertNull(savedIntent.extras)
        assertTrue(shadowOf(contentIntent).isImmutable)
    }

    @Test
    fun `running notification keeps the immutable cancellation action`() {
        val requestId = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val expectedIntent = PrivilegeSweepCancelReceiver.intent(context, requestId)
        val actionIntent =
            requireNotNull(
                notifications.running(requestId, "com.example.app").actions
            ).single().actionIntent
        val savedIntent = shadowOf(actionIntent).savedIntent

        assertEquals(expectedIntent.component, savedIntent.component)
        assertEquals(expectedIntent.action, savedIntent.action)
        assertEquals(expectedIntent.extras?.keySet(), savedIntent.extras?.keySet())
        val extraKey = requireNotNull(expectedIntent.extras).keySet().single()
        assertEquals(expectedIntent.getStringExtra(extraKey), savedIntent.getStringExtra(extraKey))
        assertTrue(shadowOf(actionIntent).isImmutable)
    }
}
