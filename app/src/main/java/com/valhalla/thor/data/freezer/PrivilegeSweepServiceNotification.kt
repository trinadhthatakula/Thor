// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.valhalla.thor.HomeActivity
import com.valhalla.thor.R
import com.valhalla.thor.data.service.FOREGROUND_PENDING_INTENT_FLAGS
import com.valhalla.thor.data.service.ForegroundPendingIntentNamespace
import com.valhalla.thor.data.service.QueueNotificationSnapshot
import com.valhalla.thor.data.service.queueNotificationStatus
import com.valhalla.thor.data.service.PRIVILEGED_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.presentation.launcher.TaskQueueLaunchActivity
import java.util.UUID

internal class PrivilegeSweepServiceNotification(
    private val context: Context,
) {
    fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                PRIVILEGED_FOREGROUND_CHANNEL_ID,
                NotificationManager.IMPORTANCE_LOW,
            )
                .setName(context.getString(R.string.privilege_queue_notification_channel_name))
                .setDescription(
                    context.getString(R.string.privilege_queue_notification_channel_description)
                )
                .build()
        )
    }

    fun preparing(): Notification = baseBuilder()
        .setContentTitle(context.getString(R.string.privilege_queue_notification_title))
        .setContentText(context.getString(R.string.task_state_starting))
        .setContentIntent(genericContentIntent())
        .build()

    fun running(requestId: UUID, packageName: String): Notification = runningBuilder(requestId, packageName).build()

    fun running(snapshot: QueueNotificationSnapshot): Notification =
        runningBuilder(snapshot.task.taskId, context.queueNotificationStatus(snapshot))
            .setSubText(context.resources.getQuantityString(
                R.plurals.task_queue_notification_later_count,
                snapshot.laterQueuedTasks, snapshot.laterQueuedTasks,
            ))
            .build()

    private fun runningBuilder(requestId: UUID, packageName: String): NotificationCompat.Builder = baseBuilder()
        .setContentTitle(context.getString(R.string.privilege_queue_notification_title))
        .setContentText(packageName)
        .setContentIntent(taskContentIntent(requestId))
        .addAction(
            0,
            context.getString(R.string.task_queue_notification_cancel),
            PendingIntent.getBroadcast(
                context,
                ForegroundPendingIntentNamespace.PRIVILEGED_CANCELLATION.requestCode(requestId),
                PrivilegeSweepCancelReceiver.intent(context, requestId),
                FOREGROUND_PENDING_INTENT_FLAGS,
            ),
        )

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, PRIVILEGED_FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.frozen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun genericContentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        ForegroundPendingIntentNamespace.PRIVILEGED_CONTENT.requestCode(PREPARING_ID),
        Intent(context, HomeActivity::class.java),
        FOREGROUND_PENDING_INTENT_FLAGS,
    )

    private fun taskContentIntent(requestId: UUID): PendingIntent = PendingIntent.getActivity(
        context,
        ForegroundPendingIntentNamespace.PRIVILEGED_CONTENT.requestCode(requestId),
        Intent(context, TaskQueueLaunchActivity::class.java)
            .putExtra(TaskQueueLaunchActivity.EXTRA_TASK_ID, requestId.toString()),
        FOREGROUND_PENDING_INTENT_FLAGS,
    )

    companion object {
        const val NOTIFICATION_ID = 1301
        private val PREPARING_ID = UUID(0L, 1L)
    }
}
