// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.backup.job

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.presentation.launcher.ShareHandoffActivity
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReadyShareNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `ready notification offers immutable UUID only foreground handoff without launching it`() {
        val task = UUID.randomUUID()
        val notification = ReadyShareNotification(context).build(task, 2, 3)
        assertEquals(context.getString(R.string.task_queue_notification_ready_partial_title), notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("2 of 3 files are ready.", notification.extras.getString(Notification.EXTRA_TEXT))
        val pending = notification.contentIntent
        assertTrue("Robolectric must execute this assertion on API 31+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(pending.isImmutable)
        }
        val intent = shadowOf(pending).savedIntent
        assertEquals(ShareHandoffActivity::class.java.name, intent.component!!.className)
        assertEquals(setOf(ShareHandoffActivity.EXTRA_TASK_ID), intent.extras!!.keySet())
        assertEquals(task.toString(), intent.getStringExtra(ShareHandoffActivity.EXTRA_TASK_ID))
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test fun `ready notification uses singular and plural prepared file counts`() {
        val notifications = ReadyShareNotification(context)
        for ((prepared, total, expected) in listOf(
            Triple(1, 1, "1 prepared file is ready."),
            Triple(2, 2, "2 prepared files are ready."),
            Triple(1, 3, "1 of 3 files is ready."),
            Triple(2, 3, "2 of 3 files are ready."),
        )) {
            val notification = notifications.build(UUID.randomUUID(), prepared, total)
            assertEquals(expected, notification.extras.getString(Notification.EXTRA_TEXT))
        }
    }

    @Test fun `two ready task notifications retain independent handoff identities`() {
        val notifications = ReadyShareNotification(context)
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0001-0000-000000000000")
        assertEquals(first.hashCode(), second.hashCode())
        val a = notifications.build(first, 1, 1).contentIntent
        val b = notifications.build(second, 1, 1).contentIntent
        assertNotEquals(a, b)
        assertEquals(first.toString(), shadowOf(a).savedIntent.getStringExtra(ShareHandoffActivity.EXTRA_TASK_ID))
    }
}
