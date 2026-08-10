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
) : CoroutineWorker(appContext, params) {

    protected abstract val kind: ThorJobKind

    /** Shown before the job knows anything about sizes. */
    protected abstract val initialLabel: String

    protected abstract suspend fun runJob(): Result

    final override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(
            kind,
            ThorJobProgress(ThorJobStage.PREPARING, initialLabel),
            id,
        )

    final override suspend fun doWork(): Result {
        // On API 31+ a foreground service cannot be started from the background, and a user who
        // backgrounds Thor between tapping and this line makes that a real outcome. The job then runs
        // without a foreground notification — more killable, but running — rather than crashing
        // before it starts.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Logger.e(TAG, "${kind.id}: continuing without a foreground notification", it) }

        return try {
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
            // Runs on cancellation too, because the rethrow above passes through it. The UI reads
            // WorkManager's own persisted `WorkInfo.State` for the terminal outcome, so dropping the
            // in-memory progress here loses nothing and bounds the map.
            registry.clear(id)
        }
    }

    /**
     * Report progress to the UI and the notification.
     *
     * `setProgress` is deliberately absent — see [JobRegistry]. Calling it here would put an SQLite
     * write on the copy loop's hot path and cap observed updates at roughly one a second.
     */
    protected fun publish(progress: ThorJobProgress) {
        registry.publish(id, progress)
        notifications.update(kind, progress, id)
    }
}
