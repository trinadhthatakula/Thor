// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import org.koin.core.annotation.Single
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Which sheet a running job's notification should reopen, and with what.
 *
 * Not a route and not `Parcelable`: this never leaves the process. See [JobSheetTargets] for why.
 */
sealed interface JobSheetTarget {

    val kind: ThorJobKind

    /** [appLabel] is the app's real name, because `AppBackupViewModel.start` writes it into state verbatim. */
    data class Backup(val packageName: String, val appLabel: String) : JobSheetTarget {
        override val kind = ThorJobKind.ARCHIVE_BACKUP
    }

    /** [uriString] is the archive the job is reading — the same string the sheet would get from the picker. */
    data class Restore(val uriString: String) : JobSheetTarget {
        override val kind = ThorJobKind.ARCHIVE_RESTORE
    }
}

/**
 * The handoff from a running job to the UI that a tap on its notification asks for.
 *
 * **Why a holder rather than intent extras.** `HomeActivity` is `standard` launchMode and there is no
 * `onNewIntent` anywhere in `app/src/main` — `pendingRestoreUri` is read once, `by lazy`, from the
 * intent the activity was *created* with. So a notification that resumes an existing task delivers its
 * extras to nothing: either they are dropped, or a component-targeted intent stacks a second
 * `HomeActivity` on top of the live one. Both are worse than carrying the payload in memory. The
 * notification therefore carries only [ThorJobKind.id]; everything the sheet needs is looked up here.
 *
 * The consequence is honest and deliberate: if Thor's process has died, [live] is empty and
 * [requestOpen] returns false, so the tap just opens the app. A job whose notification is showing has
 * a foreground service holding the process up, so that window is narrow — and the alternative
 * (persisting a SAF URI whose grant is scoped to the task that received it) would produce a sheet that
 * cannot read its own archive.
 *
 * `CONFLATED`, so a tap that lands before `MainViewModel` exists (app lock, or the process starting to
 * host the trampoline) is held rather than lost, and a second tap replaces the first instead of
 * queueing a duplicate sheet.
 *
 * The channel carries the **kind**, not the target. Delivery therefore resolves against [live] at the
 * moment the UI is listening, which is the only way to get two things right at once: a tap held through
 * the app-lock screen must not open a sheet for a job that finished while the user was unlocking, and a
 * tap that lands between a worker's two [set] calls must show the label the worker has *now*, not the
 * package name it had then. A buffered payload gets both wrong, and the first one silently — the sheet
 * opens on a dead job and simply fails to read its archive.
 */
@Single
class JobSheetTargets {

    /**
     * The live target per kind, tagged with the id of the job that owns the slot.
     *
     * [jobId] is what makes [clear] safe, and it is not decoration: `JobSheetTarget` is a data class, so
     * a `remove(kind, target)` keyed on the target alone compares by **value**. Cancel a backup and
     * immediately restart the same app and both workers hold an equal `Backup(pkg, label)` — the
     * predecessor's cleanup would then match the successor's entry and erase it, which is the exact
     * stranding the compare-and-remove exists to prevent. A `WorkSpec` id is unique per work request and
     * stable across a retry of one, so it distinguishes the pair the value cannot.
     */
    private class Live(val jobId: UUID, val target: JobSheetTarget)

    private val live = ConcurrentHashMap<ThorJobKind, Live>()

    private val _requests = Channel<ThorJobKind>(Channel.CONFLATED)

    /**
     * Emits once per notification tap on a job that is **still running when the UI collects it**.
     *
     * `mapNotNull` is the liveness re-check: a request whose job has since ended resolves to nothing and
     * the tap degrades to the plain resume the trampoline already performed.
     */
    val requests: Flow<JobSheetTarget> = _requests.receiveAsFlow().mapNotNull { live[it]?.target }

    /**
     * Publishes [target] as [jobId]'s, replacing whatever held that kind's slot.
     *
     * A worker calls this as it starts, and again if it learns a better label. Both calls pass the same
     * [jobId], so the second is an update rather than a second owner.
     */
    fun set(jobId: UUID, target: JobSheetTarget) {
        live[target.kind] = Live(jobId, target)
    }

    /**
     * Drops [kind]'s target **only if [jobId] still owns it**.
     *
     * Jobs of one kind serialise through `THOR_JOB_CHAIN`, but `APPEND_OR_REPLACE` and cancellation both
     * mean a finishing worker's `finally` can run after its successor has published. An unconditional
     * `remove(kind)` there would erase the running job's target and leave its notification opening
     * nothing; so would a compare on the target's value, whenever the two jobs describe the same work.
     *
     * `computeIfPresent` rather than a get-then-remove: returning null from the remapping function
     * removes the mapping, and `ConcurrentHashMap` holds the bin lock across the test and the removal,
     * so a successor publishing concurrently either loses the race cleanly or is never seen by it.
     *
     * Safe to call from a worker that never published — no entry carries its id, so nothing matches.
     */
    fun clear(kind: ThorJobKind, jobId: UUID) {
        live.computeIfPresent(kind) { _, current -> if (current.jobId == jobId) null else current }
    }

    /**
     * Asks the UI to open [kind]'s sheet.
     *
     * @return false when no job of that kind is live — nothing was requested and the caller should
     *   fall back to plain "open the app". A true is not a promise that a sheet appears: the job can
     *   still end between here and the collector, which [requests] resolves by dropping the request.
     */
    fun requestOpen(kind: ThorJobKind): Boolean {
        if (!live.containsKey(kind)) return false
        return _requests.trySend(kind).isSuccess
    }
}
