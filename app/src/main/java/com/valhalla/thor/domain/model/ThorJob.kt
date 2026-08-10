// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The one unique work name every archive job shares.
 *
 * Deliberately does **not** name the target. With `ExistingWorkPolicy.APPEND_OR_REPLACE` this makes
 * runs *serialise*, which is what holds peak disk at one storage class however many backups the user
 * starts. A per-package name would let two multi-gigabyte captures run at once.
 *
 * `APPEND_OR_REPLACE` rather than `APPEND` because the chain must not be wedged by a job that failed
 * or was cancelled — replace is the escape hatch that keeps the queue usable.
 */
const val THOR_JOB_CHAIN = "thor.job.chain"

/** Set on a failed job's output `Data` so the UI can say what went wrong instead of "failed". */
const val JOB_ERROR_KEY = "thor.job.error"

/**
 * The long-running jobs Thor runs through WorkManager.
 *
 * Two for now. Exports and bulk actions are meant to join them — that is why nothing in this file or
 * in `ThorJobWorker` mentions archives.
 */
enum class ThorJobKind(val id: String) {
    ARCHIVE_BACKUP("archive-backup"),
    ARCHIVE_RESTORE("archive-restore"),
}

enum class ThorJobStage {
    PREPARING,
    MEASURING,
    CAPTURING,
    WRITING,
    INSTALLING,
    RESTORING,
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
