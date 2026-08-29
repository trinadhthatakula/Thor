// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

internal fun interface ThorJobNotificationCapability {
    fun canPostJobs(): Boolean
}

/** Pure half of [ThorJobNotifications.canPostJobs], kept visible to JVM policy tests. */
internal fun jobNotificationsAvailable(
    appNotificationsEnabled: Boolean,
    postNotificationsGranted: Boolean,
    channelImportance: Int?,
): Boolean = appNotificationsEnabled &&
        postNotificationsGranted &&
        channelImportance != null &&
        channelImportance != NotificationManager.IMPORTANCE_NONE

@Single(binds = [ThorJobNotificationCapability::class])
class ThorJobNotifications(private val context: Context) : ThorJobNotificationCapability {

    // Hoisted out of the per-tick path — each from() call is a system service lookup.
    private val notificationManager = NotificationManagerCompat.from(context)

    /**
     * The tap targets, built once per kind rather than per tick.
     *
     * [build] runs up to once a second for the whole of a multi-gigabyte job, and
     * `PendingIntent.getActivity` is an IPC to ActivityManager. Eager and immutable rather than a lazy
     * cache because two IPCs at construction cost less than reasoning about a map mutated from a
     * worker thread — and the only thing that constructs `ThorJobNotifications` is a worker Koin builds
     * when WorkManager actually runs a job, so this is never on the launch path. (Not the trampoline,
     * which an earlier version of this sentence also listed: `JobSheetLaunchActivity` resolves
     * `JobSheetTargets` and nothing else, so it pays for none of this.)
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
                // The notification id doubles as the request code, so every kind gets a distinct
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

    /**
     * Whether a background job can keep its required user-visible surface for its whole lifetime.
     *
     * Channel creation comes first deliberately. On API 26+ a missing channel is not evidence that
     * the user disabled it, and querying before Thor has registered the channel would conflate those
     * two states. An existing user-disabled channel keeps its importance when re-created, so the
     * subsequent lookup still observes that decision.
     */
    override fun canPostJobs(): Boolean {
        ensureChannel()
        val postNotificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val channelImportance = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            NotificationManager.IMPORTANCE_LOW
        } else {
            notificationManager.getNotificationChannelCompat(CHANNEL_ID)?.importance
        }
        return jobNotificationsAvailable(
            appNotificationsEnabled = notificationManager.areNotificationsEnabled(),
            postNotificationsGranted = postNotificationsGranted,
            channelImportance = channelImportance,
        )
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
     * Leave a one-line outcome in the shade after a job ends.
     *
     * The gap this closes is that the ongoing row is the *only* surface a background job has, and
     * [ThorJobWorker]'s `finally` removes it. A job that finished while Thor was not on screen
     * therefore reported to nobody: `ThorJobStatus.Succeeded` is observed by a live ViewModel and by
     * nothing else, so the shade simply went quiet and the user is left to guess.
     *
     * It matters most on the path that has no other reporter at all. **WorkManager discards a
     * cancelled worker's `Result`** — `WorkerWrapper` records CANCELLED and drops the output `Data` —
     * so a sweep stopped halfway cannot report "7 of 20 done" through `Result.success(...)`. Calling
     * this before returning is the one way those counts survive.
     *
     * Transient and quiet by construction: [TIMEOUT_MS] so a stale outcome does not sit in the shade
     * for a day, `setAutoCancel` so a tap clears it, and the same IMPORTANCE_LOW channel as the
     * progress row, so it neither makes a sound nor peeks. It reuses that channel rather than
     * `BulkResultNotifier`'s deliberately — a user who silenced *"Background jobs"* has said they do
     * not want to hear about jobs, and routing the ending around that switch would be a way of
     * ignoring them.
     *
     * Guarded exactly like [update]: this can be called from a `finally`, where an uncaught
     * [SecurityException] from a permission revoked mid-job would replace the job's real outcome.
     */
    fun postResult(kind: ThorJobKind, message: String) {
        if (!notificationManager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconFor(kind))
            .setContentTitle(message)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(TIMEOUT_MS)
            // The same target as the progress row: whatever sheet this kind of job belongs to. By the
            // time a user taps it, `JobSheetTargets` has been cleared for the finished job — so this
            // opens the app rather than that sheet. Acceptable, and better than no target at all.
            .setContentIntent(contentIntents.getValue(kind))
            .build()
        try {
            notificationManager.notify(resultNotificationId(kind), notification)
        } catch (e: SecurityException) {
            Logger.e(TAG, "${kind.id}: result notify() denied after revocation", e)
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
            setSmallIcon(iconFor(kind))
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
        ThorJobKind.APP_EXPORT -> R.string.job_exporting
        ThorJobKind.PRIVILEGE_SWEEP -> R.string.freezer
    }

    /**
     * The shade's small icon, per kind.
     *
     * Both archive kinds get `settings_backup_restore`, which is what that Material symbol is for.
     * This used to be `R.drawable.frozen` for every job — a snowflake, borrowed from
     * `BulkResultNotifier` where it means *frozen app*, sitting on top of a backup. It was never a
     * deliberate choice: the comment beside it said only that no dedicated icon existed.
     *
     * `thor_mono` is not a candidate despite being the obvious one. It is the adaptive icon's
     * monochrome layer at 200dp, drawn with the safe-zone padding that format requires; scaled into a
     * 24dp status bar slot it renders as a speck. A notification small icon has to be a 24dp asset
     * drawn for 24dp.
     *
     * An export gets `arrow_downward` rather than the archive symbol, because in the shade the icon
     * is the only thing distinguishing two of Thor's rows before either is expanded — and a user who
     * started an export and a backup should be able to tell which one is still going.
     */
    private fun iconFor(kind: ThorJobKind) = when (kind) {
        ThorJobKind.ARCHIVE_BACKUP, ThorJobKind.ARCHIVE_RESTORE -> R.drawable.settings_backup_restore
        ThorJobKind.APP_EXPORT -> R.drawable.arrow_downward
        ThorJobKind.PRIVILEGE_SWEEP -> R.drawable.frozen
    }

    /**
     * One id per [ThorJobKind], which means two jobs of the same kind would share a notification.
     *
     * Safe because of an invariant that spans this file and `ThorJob.kt`: **each kind belongs to
     * exactly one unique work name, and each of those chains is serial.** Archive kinds are appended
     * to `THOR_JOB_CHAIN`, sweep kinds to `THOR_SWEEP_CHAIN`; two chains means an archive and a sweep
     * can now run at once, but they are different kinds and so hold different ids. Two jobs of the
     * *same* kind still cannot overlap, because they are on the same chain.
     *
     * What breaks it is putting one kind on both chains, or putting the target into a unique name to
     * get parallelism — `jobTag(kind, target)` already exists and is the obvious source. Either
     * silently collapses two jobs onto one row, and the first to finish cancels the second's.
     */
    private fun notificationId(kind: ThorJobKind) = BASE_NOTIFICATION_ID + kind.ordinal

    /**
     * The id of the transient outcome row, kept in a second block so it cannot collide with the
     * ongoing row it outlives.
     *
     * It has to be a different id: [ThorJobWorker]'s `finally` calls [cancel] for the ongoing
     * notification on every terminal path, so an outcome posted under the same id would be cancelled
     * within microseconds of being posted — by the very cleanup that is supposed to follow it.
     */
    private fun resultNotificationId(kind: ThorJobKind) = BASE_RESULT_NOTIFICATION_ID + kind.ordinal

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

        /**
         * The outcome rows. 100 ids of headroom above [BASE_NOTIFICATION_ID], so the two blocks
         * cannot meet until [ThorJobKind] has a hundred entries.
         */
        const val BASE_RESULT_NOTIFICATION_ID = 1200

        /** How long an outcome row stays before the system removes it. `BulkResultNotifier`'s figure. */
        private const val TIMEOUT_MS = 10_000L
    }
}
