// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.valhalla.thor.R
import com.valhalla.thor.data.service.DATA_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.data.service.FOREGROUND_PENDING_INTENT_FLAGS
import com.valhalla.thor.data.service.ForegroundPendingIntentNamespace
import com.valhalla.thor.HomeActivity
import java.util.UUID

/** Builds the minimal notification used by the Room-backed data foreground service. */
class DataSyncServiceNotification(
    private val context: Context,
) {
    fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                DATA_FOREGROUND_CHANNEL_ID,
                NotificationManager.IMPORTANCE_LOW,
            )
                .setName(context.getString(R.string.data_queue_notification_channel_name))
                .setDescription(
                    context.getString(R.string.data_queue_notification_channel_description)
                )
                .build()
        )
    }

    fun preparing(): Notification = baseBuilder()
        .setContentTitle(context.getString(R.string.data_queue_notification_title))
        .setContentText(context.getString(R.string.task_state_starting))
        .setContentIntent(contentIntent(PREPARING_ID))
        .build()

    fun running(taskId: UUID, label: String): Notification = baseBuilder()
        .setContentTitle(context.getString(R.string.data_queue_notification_title))
        .setContentText(label)
        .setContentIntent(contentIntent(taskId))
        .addAction(
            0,
            context.getString(R.string.task_queue_notification_cancel),
            PendingIntent.getBroadcast(
                context,
                ForegroundPendingIntentNamespace.DATA_CANCELLATION.requestCode(taskId),
                DataTaskCancelReceiver.intent(context, taskId),
                FOREGROUND_PENDING_INTENT_FLAGS,
            ),
        )
        .build()

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, DATA_FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.settings_backup_restore)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun contentIntent(taskId: UUID): PendingIntent = PendingIntent.getActivity(
        context,
        ForegroundPendingIntentNamespace.DATA_CONTENT.requestCode(taskId),
        Intent(context, HomeActivity::class.java),
        FOREGROUND_PENDING_INTENT_FLAGS,
    )

    companion object {
        const val NOTIFICATION_ID = 1300
        private val PREPARING_ID = UUID(0L, 0L)
    }
}
