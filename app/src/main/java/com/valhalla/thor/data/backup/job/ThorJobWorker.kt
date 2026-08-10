// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException

private const val TAG = "ThorJobWorker"

/**
 * Minimum interval between notification updates on a single job. The in-memory [JobRegistry] is
 * updated on every [publish] call regardless; only the IPC to NotificationManagerService is throttled.
 */
private const val NOTIFICATION_INTERVAL_MS = 1_000L

/**
 * Base class for Thor's long-running work: foreground notification, progress reporting, and one
 * failure policy.
 *
 * Deliberately knows nothing about archives — exports and bulk actions are meant to move onto this.
 *
 * **No subclass may return `Result.retry()`.** Archive jobs hold their key in process memory
 * ([ArchiveKeyHolder]), so a retry after process death runs without a key and cannot succeed; and a
 * partially written destination is discarded on the way out, so there is nothing for a retry to
 * resume. Process death ends a run.
 */
abstract class ThorJobWorker(
    appContext: Context,
    params: WorkerParameters,
    private val notifications: ThorJobNotifications,
    private val registry: JobRegistry,
    private val keyHolder: ArchiveKeyHolder,
) : CoroutineWorker(appContext, params) {

    protected abstract val kind: ThorJobKind

    /** Shown before the job knows anything about sizes. */
    protected abstract val initialLabel: String

    protected abstract suspend fun runJob(): Result

    // Throttle state — one ThorJobWorker instance per WorkManager execution, so fields are safe.
    private var lastNotifyMs = 0L
    private var lastNotifyStage: ThorJobStage? = null

    final override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(
            kind,
            ThorJobProgress(ThorJobStage.PREPARING, initialLabel),
            id,
        )

    final override suspend fun doWork(): Result {
        return try {
            // setForeground is inside the outer try so that a CancellationException from it —
            // or from getForegroundInfo() — still reaches finally and all three cleanups run.
            // On API 31+ a foreground service cannot be started from the background; the inner
            // catch degrades that non-cancellation failure to running without a foreground
            // notification rather than failing the whole job. CancellationException is rethrown
            // explicitly so it is not swallowed by the inner catch(Exception).
            try {
                setForeground(getForegroundInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "${kind.id}: continuing without a foreground notification", e)
            }

            runJob()
        } catch (e: CancellationException) {
            // The user pressed Cancel, or WorkManager stopped the worker. Rethrow so the coroutine
            // machinery sees a cancellation; the subclass's own `finally` has already discarded the
            // partial destination.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "${kind.id} failed", e)
            Result.failure(workDataOf(JOB_ERROR_KEY to (e.message ?: "unknown error")))
        } finally {
            // Runs on every exit: normal return, Result.failure, exception from runJob(),
            // cancellation during runJob(), and cancellation during setForeground/getForegroundInfo().
            //
            // registry: the UI reads WorkManager's own persisted WorkInfo.State for the terminal
            // outcome, so dropping in-memory progress loses nothing and bounds the map.
            registry.clear(id)
            //
            // keyHolder: drop() ensures key material is not held beyond this job's lifetime. It
            // covers cancellation and any throw that fires before the concrete worker's own take().
            // If take() already ran, drop() is a no-op (ConcurrentHashMap.remove on an absent key).
            // The path where the job is cancelled between put() and WorkManager starting doWork()
            // cannot be covered here — Task 15's enqueue path is responsible for that branch.
            keyHolder.drop(id.toString())
            //
            // notification: always runs; idempotent. On the happy path WorkManager already cancelled
            // the id it owns via setForeground — this is a no-op there and the actual cleanup on
            // both the setForeground-failed path and the cancellation-during-setForeground path.
            notifications.cancel(kind)
        }
    }

    /**
     * Report progress to the UI and the notification.
     *
     * [registry] is updated on every call — it is a cheap in-memory [StateFlow] write and the UI
     * wants every tick. The notification is throttled: at most one update per [NOTIFICATION_INTERVAL_MS],
     * or immediately on a stage change. This keeps IPC to NotificationManagerService off the hot path;
     * without it, a gigabyte-scale copy publishing at 1 MiB chunks would fire ~1000 IPCs per job.
     *
     * `setProgress` is deliberately absent — see [JobRegistry]. Calling it here would put an SQLite
     * write on the copy loop's hot path and cap observed updates at roughly one a second.
     */
    protected fun publish(progress: ThorJobProgress) {
        registry.publish(id, progress)
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs >= NOTIFICATION_INTERVAL_MS || progress.stage != lastNotifyStage) {
            lastNotifyMs = now
            lastNotifyStage = progress.stage
            notifications.update(kind, progress, id)
        }
    }
}
