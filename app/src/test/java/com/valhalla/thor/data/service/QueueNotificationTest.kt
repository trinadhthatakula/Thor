// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.service

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.service.DataSyncServiceNotification
import com.valhalla.thor.data.freezer.PrivilegeSweepServiceNotification
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import java.util.Locale
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class QueueNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `both queue notifications render task counts independently of item progress and retain UUID intents`() {
        for ((queue, operation, label) in listOf(
            Triple(TaskQueueKind.DATA, "APP_EXPORT", "App export"),
            Triple(TaskQueueKind.PRIVILEGE, "FREEZE", "Freeze apps"),
        )) {
            val id = UUID.randomUUID()
            val task = QueuedTaskSummary(id, queue, operation, emptyList(), 10,
                TaskLifecyclePhase.RUNNING, TaskProgress(3, 100, null), "example.app",
                null, emptySet(), null, null, false)
            for ((count, expected) in listOf(0 to "0 more tasks queued", 1 to "1 more task queued", 2 to "2 more tasks queued")) {
                val snapshot = QueueNotificationSnapshot(task, count)
                val notification = if (queue == TaskQueueKind.DATA) DataSyncServiceNotification(context).running(snapshot)
                    else PrivilegeSweepServiceNotification(context).running(snapshot)
                assertEquals("$label · 3 of 100", notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(expected, notification.extras.getString(Notification.EXTRA_SUB_TEXT))
                val content = shadowOf(notification.contentIntent).savedIntent
                assertEquals(setOf("com.valhalla.thor.extra.TASK_ID"), content.extras!!.keySet())
                assertEquals(id.toString(), content.getStringExtra("com.valhalla.thor.extra.TASK_ID"))
                val cancel = shadowOf(notification.actions.single().actionIntent).savedIntent
                assertEquals(1, cancel.extras!!.size())
                assertEquals(id.toString(), cancel.extras!!.getString(cancel.extras!!.keySet().single()))
            }
        }
    }

    @Test fun `data notification shows Stopping during cancellation unwind without losing progress or identity`() {
        assertStoppingNotification(TaskQueueKind.DATA, "APP_EXPORT", "App export")
    }

    @Test fun `privilege notification shows Stopping during cancellation unwind without losing progress or identity`() {
        assertStoppingNotification(TaskQueueKind.PRIVILEGE, "FREEZE", "Freeze apps")
    }

    private fun assertStoppingNotification(queue: TaskQueueKind, operation: String, label: String) {
        val id = UUID.randomUUID()
        val task = QueuedTaskSummary(id, queue, operation, emptyList(), 10,
            TaskLifecyclePhase.RUNNING, TaskProgress(3, 100, null), "example.app",
            null, emptySet(), null, null, false)
        for ((phase, expectedText) in listOf(
            TaskLifecyclePhase.RUNNING to "$label · 3 of 100",
            TaskLifecyclePhase.STOPPING to "Stopping · $label · 3 of 100",
        )) {
            val snapshot = QueueNotificationSnapshot(task.copy(phase = phase), 2)
            val notification = if (queue == TaskQueueKind.DATA) DataSyncServiceNotification(context).running(snapshot)
                else PrivilegeSweepServiceNotification(context).running(snapshot)
            assertEquals("$queue $phase text", expectedText, notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals("2 more tasks queued", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
            val contentPending = notification.contentIntent
            val cancelPending = notification.actions.single().actionIntent
            assertTrue("Robolectric must verify immutable intents on API 31+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertTrue(contentPending.isImmutable)
                assertTrue(cancelPending.isImmutable)
            }
            val content = shadowOf(contentPending).savedIntent
            assertEquals(setOf("com.valhalla.thor.extra.TASK_ID"), content.extras!!.keySet())
            assertEquals(id.toString(), content.getStringExtra("com.valhalla.thor.extra.TASK_ID"))
            val cancel = shadowOf(cancelPending).savedIntent
            assertEquals(1, cancel.extras!!.size())
            assertEquals(id.toString(), cancel.extras!!.getString(cancel.extras!!.keySet().single()))
        }
    }

    @Test fun `backup notification uses backup icon`() =
        assertOperationIcon(TaskQueueKind.DATA, "ARCHIVE_BACKUP", R.drawable.settings_backup_restore)

    @Test fun `restore notification uses backup icon`() =
        assertOperationIcon(TaskQueueKind.DATA, "ARCHIVE_RESTORE", R.drawable.settings_backup_restore)

    @Test fun `export notification uses download icon`() =
        assertOperationIcon(TaskQueueKind.DATA, "APP_EXPORT", R.drawable.arrow_downward)

    @Test fun `share preparation notification uses share icon`() =
        assertOperationIcon(TaskQueueKind.DATA, "SHARE_PREPARE", R.drawable.share)

    @Test fun `freeze notification uses freeze icon`() =
        assertOperationIcon(TaskQueueKind.PRIVILEGE, "FREEZE", R.drawable.frozen)

    @Test fun `unfreeze notification uses unfreeze icon`() =
        assertOperationIcon(TaskQueueKind.PRIVILEGE, "UNFREEZE", R.drawable.freeze_off)

    @Test fun `cache clearing notification uses clear icon`() =
        assertOperationIcon(TaskQueueKind.PRIVILEGE, "CLEAR_CACHE", R.drawable.clear_all)

    @Test fun `Fix Store notification uses install icon instead of freeze icon`() =
        assertOperationIcon(TaskQueueKind.PRIVILEGE, "REINSTALL", R.drawable.apk_install)

    @Test fun `unknown operations use a neutral icon in either queue`() {
        TaskQueueKind.entries.forEach { queue ->
            assertOperationIcon(queue, "FUTURE_OPERATION", R.drawable.list_alt)
        }
    }

    @Test fun `preparing notifications do not guess an operation`() {
        assertEquals(R.drawable.list_alt, DataSyncServiceNotification(context).preparing().smallIcon.resId)
        assertEquals(R.drawable.list_alt, PrivilegeSweepServiceNotification(context).preparing().smallIcon.resId)
    }

    @Test fun `claim notifications without operation metadata do not guess from labels`() {
        val id = UUID(0, 42)
        assertEquals(R.drawable.list_alt,
            DataSyncServiceNotification(context).running(id, "FREEZE").smallIcon.resId)
        assertEquals(R.drawable.list_alt,
            PrivilegeSweepServiceNotification(context).running(id, "ARCHIVE_BACKUP").smallIcon.resId)
    }

    @Test fun `notification icon follows active task handover not queued or terminal tasks`() {
        val export = iconTask(TaskQueueKind.DATA, "APP_EXPORT")
        val share = export.copy(taskId = UUID(0, 43), operationId = "SHARE_PREPARE",
            phase = TaskLifecyclePhase.QUEUED)
        val backup = export.copy(taskId = UUID(0, 44), operationId = "ARCHIVE_BACKUP",
            phase = TaskLifecyclePhase.SUCCEEDED)
        val builder = DataSyncServiceNotification(context)
        val snapshot = requireNotNull(queueNotificationSnapshot(export.taskId, listOf(export, share, backup)))
        assertEquals(R.drawable.arrow_downward, builder.running(snapshot).smallIcon.resId)
        val handedOver = listOf(export.copy(phase = TaskLifecyclePhase.SUCCEEDED),
            share.copy(phase = TaskLifecyclePhase.RUNNING), backup)
        assertNull(queueNotificationSnapshot(export.taskId, handedOver))
        assertEquals(R.drawable.share, builder.running(requireNotNull(
            queueNotificationSnapshot(share.taskId, handedOver),
        )).smallIcon.resId)
        assertNull(queueNotificationSnapshot(null, handedOver))
    }

    private fun assertOperationIcon(queue: TaskQueueKind, operation: String, expectedIcon: Int) {
        val task = iconTask(queue, operation)
        for (phase in listOf(TaskLifecyclePhase.RUNNING, TaskLifecyclePhase.STOPPING)) {
            for (count in listOf(0, 2)) {
                val snapshot = QueueNotificationSnapshot(task.copy(phase = phase), count)
                val notification = if (queue == TaskQueueKind.DATA) DataSyncServiceNotification(context).running(snapshot)
                    else PrivilegeSweepServiceNotification(context).running(snapshot)
                assertEquals("$operation $phase queued=$count", expectedIcon, notification.smallIcon.resId)
            }
        }
    }

    private fun iconTask(queue: TaskQueueKind, operation: String) = QueuedTaskSummary(
        UUID(0, 42), queue, operation, emptyList(), 10, TaskLifecyclePhase.RUNNING,
        TaskProgress(3, 100, null), "example.app", null, emptySet(), null, null, false,
    )

    @Test fun `quantity resources select authored locale forms for representative counts`() {
        val samples = mapOf(
            "en" to listOf(1 to "1 more task queued", 2 to "2 more tasks queued"),
            "es" to listOf(1 to "1 tarea más en cola", 2 to "2 tareas más en cola"),
            "fr" to listOf(1 to "1 tâche de plus en attente", 2 to "2 tâches de plus en attente"),
            "pt-PT" to listOf(1 to "Mais 1 tarefa em fila", 2 to "Mais 2 tarefas em fila"),
            "pt-BR" to listOf(1 to "Mais 1 tarefa na fila", 2 to "Mais 2 tarefas na fila"),
            "zh-CN" to listOf(1 to "还有 1 项任务已排队", 2 to "还有 2 项任务已排队"),
            "pl" to listOf(1 to "Jeszcze 1 zadanie w kolejce", 2 to "Jeszcze 2 zadania w kolejce",
                5 to "Jeszcze 5 zadań w kolejce", 22 to "Jeszcze 22 zadania w kolejce"),
            "ar" to listOf(0 to "لا توجد مهام أخرى في قائمة الانتظار (%1\$d)",
                1 to "مهمة أخرى في قائمة الانتظار (%1\$d)",
                2 to "مهمتان أخريان في قائمة الانتظار (%1\$d)",
                3 to "%1\$d مهام أخرى في قائمة الانتظار",
                11 to "%1\$d مهمة أخرى في قائمة الانتظار",
                100 to "%1\$d مهمة أخرى في قائمة الانتظار"),
        )
        for ((tag, cases) in samples) {
            val locale = Locale.forLanguageTag(tag)
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(locale)
            })
            for ((quantity, expected) in cases) {
                // Arabic formatting uses locale digits; category wording is independently specified above.
                val text = if (tag == "ar") String.format(locale, expected, quantity) else expected
                assertEquals("$tag quantity=$quantity", text, localized.resources.getQuantityString(
                    R.plurals.task_queue_notification_later_count, quantity, quantity,
                ))
            }
        }
    }
}
