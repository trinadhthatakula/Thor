// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.valhalla.thor.presentation.launcher.JobSheetLaunchActivity
import com.valhalla.thor.util.Logger
import java.util.UUID
import org.koin.core.annotation.Single

private const val TAG = "ThorJobNotifications"

@Single
class ThorJobNotifications(private val context: Context) {

    // Hoisted out of the per-tick path — each from() call is a system service lookup.
    private val notificationManager = NotificationManagerCompat.from(context)

    /**
     * The tap targets, built once per kind rather than per tick.
     *
     * [build] runs up to once a second for the whole of a multi-gigabyte job, and
     * `PendingIntent.getActivity` is an IPC to ActivityManager. Eager and immutable rather than a lazy
     * cache because two IPCs at construction cost less than reasoning about a map mutated from a
     * worker thread — and `ThorJobNotifications` is only constructed when a job or the trampoline
     * first asks for it, not on the launch path.
     *
     * Naming a `presentation` class from `data` is the one upward reference here, and it is the
     * existing shape for this: `AnyFileOpenerManager` names `PortableInstallerActivity` for the same
     * reason — an `Intent` needs a component, and the component is an Activity. The alternative is a
     * `SystemGateway`-style interface whose only implementation returns one class literal.
     */
    private val contentIntents: Map<ThorJobKind, PendingIntent> =
        ThorJobKind.entries.associateWith { kind ->
            PendingIntent.getActivity(
                context,
                // The notification id doubles as the request code, so the two kinds get distinct
                // PendingIntents instead of one overwriting the other's extras. Request code 0 is
                // BulkResultNotifier's; these start at BASE_NOTIFICATION_ID.
                notificationId(kind),
                Intent(context, JobSheetLaunchActivity::class.java)
                    .putExtra(JobSheetLaunchActivity.EXTRA_JOB_KIND, kind.id),
                // IMMUTABLE: nothing outside Thor has any business filling in this intent, and API 31+
                // requires one of the two to be named explicitly. UPDATE_CURRENT so a rebuilt intent
                // replaces the extras of a notification the system is still showing.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    init {
        // Channel creation is idempotent; calling it once here avoids an IPC on every notification
        // post rather than relying on the caller to guard it.
        ensureChannel()
    }

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

    /**
     * Post or refresh the progress notification.
     *
     * Returns early when notifications are globally disabled. That is a cheap IPC guard — posting to
     * a blocked channel is a no-op anyway — and it is **not** a gate on `POST_NOTIFICATIONS`
     * specifically.
     *
     * Be exact about what the user is left with on that path, because the sentence this replaced was
     * wrong in both halves. The system's Task Manager row is an **API 33+** surface; Thor's minSdk is
     * 28, so on API 28–32 a job whose notification is blocked has no user-visible surface at all —
     * no indication it is running, and no way to stop it short of force-stopping Thor. And the row
     * that does exist on 33+ renders the app and a **Stop** button; it does not render a
     * notification's actions, so the cancel `PendingIntent` [build] attaches does not reach it. Its
     * Stop force-stops the process, which runs none of [ThorJobWorker]'s `finally` — no registry
     * clear, no key drop, no notification cancel. §8.5's breadcrumb is what covers that for a
     * restore; a backup relies on the launch sweep.
     *
     * `POST_NOTIFICATIONS` can be revoked while the job runs. If a revocation races a [notify] call,
     * the resulting [SecurityException] is caught so that a revoked permission does not fail the backup.
     */
    fun update(kind: ThorJobKind, progress: ThorJobProgress, jobId: UUID) {
        if (!notificationManager.areNotificationsEnabled()) return
        try {
            notificationManager.notify(notificationId(kind), build(kind, progress, jobId))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between areNotificationsEnabled() and notify(). The backup
            // continues; the shade row is lost for the remainder of this run. areNotificationsEnabled
            // can race a revocation — this is the BulkResultNotifier precedent applied to a longer window.
            Logger.e(TAG, "${kind.id}: notify() denied after revocation, continuing", e)
        }
    }

    /**
     * Cancel the notification for [kind].
     *
     * On the happy path WorkManager cancels the id it owns via setForeground when the job finishes.
     * On the setForeground-failed path nothing else cancels it, so [ThorJobWorker] calls this from
     * its `finally` on every terminal path. Idempotent — a double cancel is safe.
     */
    fun cancel(kind: ThorJobKind) {
        notificationManager.cancel(notificationId(kind))
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
            // Reopens the sheet this job belongs to. Not setAutoCancel(true) with it: the job is still
            // running after the tap, and the row is this job's only surface — dismissing it on tap
            // would leave a foreground service with nothing showing for it.
            setContentIntent(contentIntents.getValue(kind))
            // An unknown total is an indeterminate bar, never a bar sitting at 0%.
            val percent = progress.percent
            if (percent == null) setProgress(0, 0, true) else setProgress(100, percent, false)
            // createCancelPendingIntent needs no receiver of Thor's own, and it cancels the work
            // rather than just dismissing the notification. It is a live cancel of a running job:
            // ThorJobLauncher.cancel is not the only route to a CANCELLED WorkInfo, and both
            // watchers' `workerRan` arms exist for the state this action can leave behind.
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

    /**
     * One id per [ThorJobKind], which means two jobs of the same kind would share a notification.
     *
     * Safe only because `THOR_JOB_CHAIN` carries no target: every archive job is appended to one
     * chain, so at most one runs at a time. The day someone puts the target into the unique name to
     * get parallelism — `jobTag(kind, target)` already exists and is the obvious source — this
     * silently collapses two jobs onto one row, and the first to finish cancels the second's.
     */
    private fun notificationId(kind: ThorJobKind) = BASE_NOTIFICATION_ID + kind.ordinal

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
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
