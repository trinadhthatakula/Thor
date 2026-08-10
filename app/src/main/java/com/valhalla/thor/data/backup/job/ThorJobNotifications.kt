// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import java.util.UUID
import org.koin.core.annotation.Single

@Single
class ThorJobNotifications(private val context: Context) {

    fun foregroundInfo(
        kind: ThorJobKind,
        progress: ThorJobProgress,
        jobId: UUID,
    ): ForegroundInfo {
        val notification = build(kind, progress, jobId)
        val id = notificationId(kind)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Must match the manifest's android:foregroundServiceType on WorkManager's
            // SystemForegroundService, or targetSdk 34+ throws MissingForegroundServiceTypeException
            // the moment setForeground() runs. Task 1 added that overlay.
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    // MissingPermission: areNotificationsEnabled() is the runtime guard. NotificationManagerCompat
    // is annotated @RequiresPermission(POST_NOTIFICATIONS) but lint cannot model that check as
    // satisfying the annotation — it sees the method call, not the guard above it. A foreground
    // service's own notification is exempt from POST_NOTIFICATIONS on the platform, but this call
    // updates the notification content directly; the guard above handles API 33+ runtime revocation.
    @SuppressLint("MissingPermission")
    fun update(kind: ThorJobKind, progress: ThorJobProgress, jobId: UUID) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        // No POST_NOTIFICATIONS gate: a foreground service's own notification is exempt from that
        // permission, and gating it would silently drop the progress UI on API 33+ devices where the
        // user declined the prompt Thor shows for the bulk-result notification.
        if (!manager.areNotificationsEnabled()) return
        manager.notify(notificationId(kind), build(kind, progress, jobId))
    }

    private fun build(kind: ThorJobKind, progress: ThorJobProgress, jobId: UUID) =
        NotificationCompat.Builder(context, CHANNEL_ID).apply {
            // BulkResultNotifier uses R.drawable.frozen; no ic_thor_notification exists in this build.
            setSmallIcon(R.drawable.frozen)
            setContentTitle(context.getString(titleFor(kind)))
            setContentText(progress.label)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            // An unknown total is an indeterminate bar, never a bar sitting at 0%.
            val percent = progress.percent
            if (percent == null) setProgress(0, 0, true) else setProgress(100, percent, false)
            // createCancelPendingIntent needs no receiver of Thor's own, and it cancels the work
            // rather than just dismissing the notification.
            addAction(
                0,
                context.getString(android.R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(jobId),
            )
        }.build()

    private fun titleFor(kind: ThorJobKind) = when (kind) {
        ThorJobKind.ARCHIVE_BACKUP -> R.string.job_backing_up
        ThorJobKind.ARCHIVE_RESTORE -> R.string.job_restoring
    }

    private fun notificationId(kind: ThorJobKind) = BASE_NOTIFICATION_ID + kind.ordinal

    private fun ensureChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
                .setName(context.getString(R.string.job_channel_name))
                .setDescription(context.getString(R.string.job_channel_description))
                .build()
        )
    }

    companion object {
        /** Distinct from `BulkResultNotifier.CHANNEL_ID` ("thor.bulk_result") — a silenced bulk channel must not silence this. */
        const val CHANNEL_ID = "thor.jobs"

        /** `BulkResultNotifier` owns 1001. */
        const val BASE_NOTIFICATION_ID = 1100
    }
}
