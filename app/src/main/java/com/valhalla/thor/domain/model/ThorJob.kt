// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The one unique work name every job that **moves bytes** shares: archives today, exports next.
 *
 * Deliberately does **not** name the target. With `ExistingWorkPolicy.APPEND_OR_REPLACE` this makes
 * runs *serialise*, which is what holds peak disk at one storage class however many backups the user
 * starts. A per-package name would let two multi-gigabyte captures run at once.
 *
 * `APPEND_OR_REPLACE` rather than `APPEND` because the chain must not be wedged by a job that failed
 * or was cancelled — replace is the escape hatch that keeps the queue usable.
 *
 * **The argument above is about disk, so it does not reach a privilege sweep.** A freeze, a suspend, a
 * force-stop and an uninstall write no bytes; putting them on this name would queue a five-second
 * sweep behind an hour-long backup for a reason that does not apply to it. They use
 * [THOR_SWEEP_CHAIN] instead.
 */
const val THOR_JOB_CHAIN = "thor.job.chain"

/**
 * The unique work name every **privilege sweep** shares — bulk freeze, unfreeze, force-stop, cache
 * clear, reinstall.
 *
 * Separate from [THOR_JOB_CHAIN] so a sweep is not queued behind a multi-gigabyte capture, and so a
 * capture is not delayed by a queue of sweeps. Same `APPEND_OR_REPLACE` policy, for the same
 * anti-wedging reason.
 *
 * Sweeps still serialise *among themselves*, and not on disk grounds:
 *
 * - Two sweeps over overlapping selections race on the same packages. A freeze landing between
 *   another sweep's `getApplicationInfo` and its `setAppDisabled` is a decision made on state that
 *   has already changed.
 * - Odin's root channel is one long-lived FIFO `su` session, so two "concurrent" root sweeps
 *   interleave into that single session anyway. The parallelism would be a fiction bought at the
 *   price of the race above.
 */
const val THOR_SWEEP_CHAIN = "thor.sweep.chain"

/** Set on a failed job's output `Data` so the UI can say what went wrong instead of "failed". */
const val JOB_ERROR_KEY = "thor.job.error"

/**
 * Set on a **succeeded** job's output `Data`: the sentences the job finished in spite of.
 *
 * A `String[]`, because a job can have more than one and `Data` has no list type. Its counterpart is
 * [JOB_ERROR_KEY] — that one says why nothing happened, this one says what happened that the user
 * still needs to know. A restore whose game data could not be placed succeeds, and the app it
 * restored starts and then crashes; without this the only record is logcat.
 */
const val JOB_WARNINGS_KEY = "thor.job.warnings"

/**
 * The long-running jobs Thor runs through WorkManager.
 *
 * Two for now. Exports and bulk actions are meant to join them — that is why nothing in this file or
 * in `ThorJobWorker` mentions archives.
 *
 * **Append only. Never insert or reorder.** `ThorJobNotifications` derives a notification id from
 * `BASE_NOTIFICATION_ID + ordinal`, and that same number is the `PendingIntent` request code for the
 * row's tap target. Inserting a kind renumbers every kind after it, which hands a live notification
 * the request code of a different job. [jobKindFromId] is deliberately immune to this — the tap extra
 * travels as [id], not as an ordinal — but the id arithmetic is not.
 */
enum class ThorJobKind(val id: String) {
    ARCHIVE_BACKUP("archive-backup"),
    ARCHIVE_RESTORE("archive-restore"),
}

/**
 * The reverse of [ThorJobKind.id], for the one place a kind crosses a process boundary: the `Intent`
 * extra a job notification's tap carries.
 *
 * Matches on [ThorJobKind.id] rather than `name` or `ordinal` on purpose. An ordinal is silently
 * wrong the day someone reorders the enum, and a `PendingIntent` outlives the code that made it —
 * `FLAG_UPDATE_CURRENT` replaces the extras of a live one, but a notification the system is still
 * showing across an app update is holding whatever the *old* build wrote. Returns null for anything
 * unrecognised, which the caller reads as "just open the app".
 */
fun jobKindFromId(id: String?): ThorJobKind? = ThorJobKind.entries.firstOrNull { it.id == id }

enum class ThorJobStage {
    PREPARING,
    MEASURING,
    CAPTURING,
    WRITING,
    INSTALLING,
    RESTORING,

    /**
     * A sweep working through its selection — freezing, clearing, force-stopping.
     *
     * One stage for the whole loop rather than one per operation, because a sweep's interesting
     * quantity is *which app of how many*, and that is [ThorJobProgress.label] and its counts. The
     * stage exists to distinguish the loop from [PREPARING] and [FINISHING] on either side of it; the
     * operation is already in the notification title.
     */
    ACTING,
    FINISHING,
}

/**
 * A tag naming one job's kind and target.
 *
 * Since every job shares [THOR_JOB_CHAIN], the chain name can no longer answer "is this package
 * already queued?". This tag can: `WorkManager.getWorkInfosByTag` finds it, which is how the UI both
 * refuses a double tap on the same app and reattaches to a running job after a rotation.
 */
fun jobTag(kind: ThorJobKind, target: String): String = "thor.job.${kind.id}.$target"

/**
 * What a running job reports.
 *
 * [completed] and [total] are unit-agnostic: callers may carry byte counts (a restore streaming a
 * large file) or class indices (a backup iterating over `DataClass.entries`). [total] == 0 means the
 * quantity is not known — [percent] is null and the UI shows an indeterminate bar.
 *
 * Never render an unknown total as 0%; that is the tri-state rule every size field on this branch
 * already carries, applied here.
 */
data class ThorJobProgress(
    val stage: ThorJobStage,
    val label: String,
    val completed: Long = 0L,
    val total: Long = 0L,
) {

    val percent: Int?
        get() = if (total > 0L) {
            // `du` reports apparent size; the tar that follows disagrees with it routinely, in both
            // directions. Clamping is the expected case, not a guard against a bug.
            ((completed * 100L) / total).coerceIn(0L, 100L).toInt()
        } else {
            null
        }
}
