// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import android.os.SystemClock
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
 * Anything a single kind of job needs on the way out goes in that subclass's [onJobFinished], not in
 * this constructor; [ArchiveKeyHolder] used to be a parameter here and was the one thing keeping this
 * class from being reusable at all.
 *
 * **No subclass may return `Result.retry()`, and a subclass must be safe to re-run regardless.**
 *
 * Those are two separate rules and the second is the one that is easy to miss. WorkManager re-runs a
 * worker whose process was killed mid-run **whatever it returned** — the ban on `retry()` only stops
 * Thor from *asking* for a re-run, it does not stop one happening. So every subclass has to be
 * idempotent: re-running it must not do damage that running it once would not.
 *
 * Archive jobs satisfy both. They hold their key in process memory ([ArchiveKeyHolder]), so a re-run
 * after process death has no key and fails before touching anything; and a partially written
 * destination is discarded on the way out, so there is nothing to resume and nothing left behind.
 *
 * A sweep has to earn it per operation. `pm install -r` and a cache clear are naturally idempotent —
 * doing them twice is doing them once. An uninstall and a force-stop are not: the second pass acts on
 * a selection whose members may no longer be in the state that made them eligible, and reports a
 * failure for work the first pass completed. A sweep of those must record what it has already done
 * where a re-run can read it, or not be put on this seam.
 */
abstract class ThorJobWorker(
    appContext: Context,
    params: WorkerParameters,
    private val notifications: ThorJobNotifications,
    private val registry: JobRegistry,
    private val sheetTargets: JobSheetTargets,
) : CoroutineWorker(appContext, params) {

    protected abstract val kind: ThorJobKind

    /** Shown before the job knows anything about sizes. */
    protected abstract val initialLabel: String

    /**
     * Whether this job runs as a foreground service.
     *
     * True for anything that moves bytes: a multi-gigabyte capture that the system freezes halfway is
     * a corrupt archive, and the FGS is what buys it the wakelock and the process priority to finish.
     *
     * False is for short privilege sweeps, and it costs them nothing they need. The progress
     * notification is **not** the foreground service — [ThorJobNotifications.update] posts through
     * `NotificationManagerCompat.notify`, so a `runsForeground = false` job still gets its shade row,
     * its progress bar and its cancel action. What it gives up is the priority and the wakelock, which
     * a sweep that finishes in seconds does not need, in exchange for not spending Thor's one
     * `dataSync` slot and not risking `ForegroundServiceStartNotAllowedException` on API 31+ for work
     * that is over before the exception would have mattered.
     */
    protected open val runsForeground: Boolean = true

    /**
     * Which sheet this job's notification reopens, or null if its input `Data` did not carry enough to
     * say. Read once at the top of [doWork]; a subclass that learns more later calls [retargetSheet].
     */
    protected abstract val sheetTarget: JobSheetTarget?

    protected abstract suspend fun runJob(): Result

    // Throttle state — one ThorJobWorker instance per WorkManager execution, so fields are safe.
    private var lastNotifyMs = 0L
    private var lastNotifyStage: ThorJobStage? = null

    /** Set by [noteResult]; posted by [doWork]'s `finally`. Same single-instance argument as above. */
    private var resultNotice: String? = null

    final override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(
            kind,
            ThorJobProgress(ThorJobStage.PREPARING, initialLabel),
            id,
        )

    final override suspend fun doWork(): Result {
        return try {
            // Before setForeground, so the notification cannot be tapped before the target it points
            // at exists. Inside the try so the finally below is the single owner of removing it.
            sheetTarget?.let(::retargetSheet)

            // setForeground is inside the outer try so that a CancellationException from it —
            // or from getForegroundInfo() — still reaches finally and all three cleanups run.
            // On API 31+ a foreground service cannot be started from the background; the inner
            // catch degrades that non-cancellation failure to running without a foreground
            // notification rather than failing the whole job. CancellationException is rethrown
            // explicitly so it is not swallowed by the inner catch(Exception).
            //
            // A runsForeground = false job skips this and nothing else: its first publish() posts the
            // same row through NotificationManagerCompat. The degraded path this catch produces is
            // exactly what such a job runs in deliberately, which is the evidence that the path works.
            if (runsForeground) {
                try {
                    setForeground(getForegroundInfo())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "${kind.id}: continuing without a foreground notification", e)
                }
            } else {
                // Post the row here rather than waiting for the subclass's first publish(). Otherwise
                // a job has no user-visible surface for however long its own setup takes, which is a
                // contract a subclass can forget; setForeground gives foreground jobs this for free
                // and a non-foreground one should not be worse off for a reason nobody can see.
                publish(ThorJobProgress(ThorJobStage.PREPARING, initialLabel))
            }

            runJob()
        } catch (e: CancellationException) {
            // The user pressed Cancel, or WorkManager stopped the worker. Rethrow so the coroutine
            // machinery sees a cancellation; the subclass's own `finally` has already discarded the
            // partial destination.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "${kind.id} failed", e)
            Result.failure(
                workDataOf(JOB_ERROR_KEY to (e.message?.boundedForJobData() ?: "unknown error"))
            )
        } finally {
            // Runs on every exit: normal return, Result.failure, exception from runJob(),
            // cancellation during runJob(), and cancellation during setForeground/getForegroundInfo().
            //
            // registry: the UI reads WorkManager's own persisted WorkInfo.State for the terminal
            // outcome, so dropping in-memory progress loses nothing and bounds the map.
            registry.clear(id)
            //
            // onJobFinished: whatever this kind of job has to release. Runs on every path doWork can
            // reach, and before the two cleanups below so a subclass cannot observe a half-torn-down
            // job. Guarded, because this is a `finally`: an override that throws would otherwise skip
            // the notification cancel below and leave an ongoing row for a job that is over — a
            // permanent, undismissable one, since setOngoing(true) means the user cannot swipe it away.
            // Losing one subclass's cleanup is recoverable; losing the base's is not.
            runCatching { onJobFinished() }
                .onFailure { Logger.e(TAG, "${kind.id}: onJobFinished threw", it) }
            //
            // notification: always runs; idempotent. On the happy path WorkManager already cancelled
            // the id it owns via setForeground — this is a no-op there and the actual cleanup on
            // both the setForeground-failed path and the cancellation-during-setForeground path.
            notifications.cancel(kind)
            //
            // outcome: after the cancel above, so the ongoing row is gone before the outcome replaces
            // it rather than the two overlapping for a frame. They hold different ids, so this is
            // ordering for the user's benefit and not a correctness requirement. Nothing is posted
            // unless the job called noteResult — see it for why archives currently do not.
            resultNotice?.let { notifications.postResult(kind, it) }
            //
            // sheetTarget: keyed on this job's id, so a successor that has already published keeps
            // its own target even when the two describe the same work and compare equal. Called
            // unconditionally — a worker that never published owns no entry, so nothing matches.
            // Must not be skipped on the cancellation path: a cancelled job's notification is gone,
            // so a target left behind would be reopened by a *future* notification of the same kind
            // and would show the wrong app.
            sheetTargets.clear(kind, id)
        }
    }

    /**
     * Ask for a one-line outcome to be left in the shade when this job ends.
     *
     * Recorded rather than posted, so that it survives the one path where a job cannot report through
     * its `Result` at all: **WorkManager discards a cancelled worker's `Result`.** A sweep that is
     * stopped after 7 of 20 apps has no way to return that count — but [doWork]'s `finally` runs on the
     * cancellation path, so a notice set before returning is still posted. Call it and then return;
     * do not try to post from a cancellation handler.
     *
     * The last call wins. A job that notes progress and then fails ends up reporting the failure,
     * which is the right way round.
     *
     * **Archive jobs deliberately do not call this yet.** Both have a sheet that reports every outcome
     * in detail, including the warning list a plain sentence would flatten, and adding a second
     * reporter to the paths a user *is* watching is a change to backup behaviour that belongs in its
     * own pass rather than in a refactor. The mechanism is here because a sweep needs it and because
     * the reason it has to work this way is only obvious while looking at `finally`.
     */
    protected fun noteResult(message: String) {
        // Capped with the `Data` helper even though this never becomes `Data` — it is the cap Thor
        // already has for "one sentence about a job", and a notification title assembled from a shell
        // error is the same unbounded input on a different path. Read the name as the size, not as the
        // destination.
        resultNotice = message.boundedForJobData()
    }

    /**
     * Release whatever this *kind* of job holds. Called from [doWork]'s `finally`, on every path.
     *
     * This is where [ArchiveKeyHolder] went when it came out of the constructor. The base class has no
     * business knowing that some jobs hold key material and others hold nothing; it only has to
     * guarantee the call happens, which the `finally` does.
     *
     * Not `suspend`, on purpose. It runs on a cancellation path, where a suspending call would either
     * throw `CancellationException` immediately or need `NonCancellable` around it — and cleanup that
     * can be interrupted is not cleanup. Anything that genuinely must suspend belongs in the
     * subclass's own `finally` inside `runJob`, where `withContext(NonCancellable)` is already the
     * shape the archive workers use.
     *
     * **Must not throw.** It is called inside a `finally`; the base guards the call so a throw cannot
     * strand the notification, but the override's own work would be abandoned partway.
     */
    protected open fun onJobFinished() {}

    /**
     * Republish this job's sheet target after learning something better than its input `Data` carried.
     *
     * A backup's `Data` holds only the package name, so the sheet a tap opens would be titled with an
     * application id until the worker resolves the real label. This is how it stops being.
     *
     * Always this worker's own [id], which is what keeps the `finally` from clearing a successor's
     * entry — see [JobSheetTargets.clear].
     */
    protected fun retargetSheet(target: JobSheetTarget) {
        sheetTargets.set(id, target)
    }

    /**
     * `Result.failure` carrying a sentence, never `Result.retry`.
     *
     * A retry reports the failure long after the moment the user was watching, which is true of every
     * job on this seam. For an archive job it also cannot succeed: the re-run is a fresh process, and
     * [ArchiveKeyHolder.take] returns null there.
     *
     * Lives here rather than at file level in the workers because `ListenableWorker.Result` is a
     * nested type: a top-level helper would have to name it fully qualified at every call site, and
     * the point of this is that a subclass never has to think about which `Result` it means.
     *
     * Declared **above** [publish]'s doc comment on purpose: Kotlin attaches the last doc comment
     * before a declaration, so inserting this between that comment and `publish` would silently
     * orphan it — the comment would vanish from `publish` in the IDE and in Dokka and read as this
     * function's preamble instead.
     */
    protected fun fail(reason: String): Result =
        Result.failure(workDataOf(JOB_ERROR_KEY to reason.boundedForJobData()))

    /**
     * Report progress to the UI and the notification.
     *
     * [registry] is updated on every call — it is a cheap in-memory [StateFlow] write and the UI
     * wants every tick. The notification is throttled: at most one update per [NOTIFICATION_INTERVAL_MS],
     * or immediately on a stage change. This keeps IPC to NotificationManagerService off the hot path;
     * without it, a gigabyte-scale copy publishing at 1 MiB chunks would fire ~1000 IPCs per job.
     *
     * `setProgress` is deliberately absent — see [JobRegistry]. Calling it here would put an SQLite
     * write on the copy loop's hot path, one per published chunk. That cost is the whole argument.
     * An earlier version of this sentence added that it would also cap observed updates at roughly
     * one a second; WorkManager makes no such promise and that guarantee was invented. The one-a-
     * second rate in this feature is the paragraph above — Thor's own, on the notification IPC.
     *
     * The throttle is on [SystemClock.elapsedRealtime], the monotonic clock, and not on wall time.
     * An NTP correction or a user setting the clock back mid-job makes a wall-clock delta negative,
     * and a negative delta never clears the interval — the shade would then stop updating for the
     * rest of a multi-gigabyte backup, recovering only at the next stage change.
     */
    protected fun publish(progress: ThorJobProgress) {
        registry.publish(id, progress)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyMs >= NOTIFICATION_INTERVAL_MS || progress.stage != lastNotifyStage) {
            lastNotifyMs = now
            lastNotifyStage = progress.stage
            notifications.update(kind, progress, id)
        }
    }
}

/**
 * The most of one sentence Thor will hand to WorkManager.
 *
 * Generous on purpose — no real message on any of these paths is close to it — because the number is
 * a ceiling, not a style rule. Four bounded warnings plus their keys is around 2 KB against `Data`'s
 * 10 KB, which leaves the whole budget's worth of headroom for a message nobody predicted.
 */
internal const val MAX_JOB_MESSAGE_CHARS = 512

/**
 * Cap a sentence before it becomes `Data`.
 *
 * `Data.Builder.build()` **throws** `IllegalStateException` above `Data.MAX_DATA_BYTES` (10 KB)
 * rather than truncating — it serialises eagerly for that reason, "so we catch Data objects that are
 * too large at build() instead of later" — and every message this feature reports travels that way.
 * Two of the three writers take a string that is only bounded by the thing that produced it:
 * `Throwable.message` in [ThorJobWorker.doWork]'s catch, and the warning list on a *successful*
 * restore. The second is the one that costs something: an overflow there is thrown out of `runJob`,
 * caught as a failure, and a restore that had already finished is reported to the user as failed —
 * which sends them to run it again over data that is already correct.
 *
 * The one input that could actually reach 10 KB has been bounded at its source (`isSafeObbLeafName`
 * now caps the leaf name that a placement warning quotes). This is the second line: it keeps the
 * guarantee at the boundary where `Data`'s rule lives, so a future message assembled from a shell
 * error, a file listing or a stack trace cannot re-open the hole somewhere upstream.
 *
 * Top-level rather than a method on [ThorJobWorker] so a JVM test can reach it — nothing inside a
 * `CoroutineWorker` is reachable without an Android runtime, and this module has no Robolectric. The
 * same reason `wrongKeyReason` is top-level.
 */
internal fun String.boundedForJobData(): String =
    if (length <= MAX_JOB_MESSAGE_CHARS) this else take(MAX_JOB_MESSAGE_CHARS - 1) + "…"
