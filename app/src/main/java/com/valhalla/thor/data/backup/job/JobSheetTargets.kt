// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.koin.core.annotation.Single
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
 */
@Single
class JobSheetTargets {

    private val live = ConcurrentHashMap<ThorJobKind, JobSheetTarget>()

    private val _requests = Channel<JobSheetTarget>(Channel.CONFLATED)

    /** Emits once per notification tap on a job that is still running. */
    val requests: Flow<JobSheetTarget> = _requests.receiveAsFlow()

    /** Replaces the target for [target]'s kind. A worker calls this as it starts, and again if it learns a better label. */
    fun set(target: JobSheetTarget) {
        live[target.kind] = target
    }

    /**
     * Drops [target] **only if it is still the live one**.
     *
     * The compare-and-remove is the point. Jobs of one kind serialise through `THOR_JOB_CHAIN`, but
     * `APPEND_OR_REPLACE` and cancellation mean a finishing worker's `finally` can run after its
     * successor has already published. A plain `remove(kind)` there would erase the running job's
     * target and leave its notification opening nothing.
     */
    fun clearIfStill(target: JobSheetTarget) {
        live.remove(target.kind, target)
    }

    /**
     * Asks the UI to open [kind]'s sheet.
     *
     * @return false when no job of that kind is live — nothing was requested and the caller should
     *   fall back to plain "open the app".
     */
    fun requestOpen(kind: ThorJobKind): Boolean {
        val target = live[kind] ?: return false
        return _requests.trySend(target).isSuccess
    }
}
