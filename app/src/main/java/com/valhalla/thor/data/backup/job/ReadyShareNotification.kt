// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.backup.job

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.service.DataSyncServiceNotification
import com.valhalla.thor.data.service.DATA_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.data.service.FOREGROUND_PENDING_INTENT_FLAGS
import com.valhalla.thor.data.service.ForegroundPendingIntentNamespace
import com.valhalla.thor.presentation.launcher.ShareHandoffActivity
import java.util.UUID
import org.koin.core.annotation.Single

/** Completion only offers a foreground action; it never launches the chooser or wakes a service. */
@Single
internal class ReadyShareNotification(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun post(taskId: UUID, readyCount: Int, totalCount: Int) {
        try {
            DataSyncServiceNotification(context).ensureChannel()
            NotificationManagerCompat.from(context).notify(taskId.toString(), NOTIFICATION_ID, build(taskId, readyCount, totalCount))
        } catch (_: Exception) {
            // Revocation must not turn a persisted ready result into a failed task.
        }
    }

    fun build(taskId: UUID, readyCount: Int, totalCount: Int): Notification {
        require(readyCount in 1..totalCount)
        val partial = readyCount < totalCount
        val title = if (partial) R.string.task_queue_notification_ready_partial_title else R.string.task_queue_notification_ready_title
        val text = if (partial) context.getString(R.string.task_queue_notification_ready_partial_text, readyCount, totalCount)
            else context.getString(R.string.task_queue_notification_ready_text, readyCount)
        val intent = ShareHandoffActivity.intent(context, taskId).apply {
            // UUID hash collisions must not retarget another task's immutable PendingIntent.
            data = "thor-share://ready/$taskId".toUri()
        }
        return NotificationCompat.Builder(context, DATA_FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.settings_backup_restore)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context,
                ForegroundPendingIntentNamespace.DATA_CONTENT.requestCode(taskId), intent, FOREGROUND_PENDING_INTENT_FLAGS))
            .build()
    }

    private companion object { const val NOTIFICATION_ID = 1301 }
}
