// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory

/**
 * §8.5's notice for a surface that is **not** the screen running the restore: the breadcrumb, but only
 * while no restore for that app is in flight.
 *
 * A breadcrumb is written at the *start* of the destructive phase and deleted when it ends well, so on
 * its own it answers "a restore for this app has begun and not yet finished" — which is what the notice
 * needs while nothing is running, and a false statement while something is. The copy it drives says
 * *"did not finish … restoring it again is the fix"*, and a live restore is neither finished nor a
 * reason to start a second one. §8.5 defines the notice as what *survives* to the next launch.
 *
 * This is not a hypothetical ordering. `ArchiveRestore` is registered as a `detailPane()`, so on an
 * expanded window the user starts a restore in the right-hand pane while the Settings section is
 * composed on the left; the breadcrumb write lands mid-restore, and without this filter the left pane
 * announces failure beside a progress bar reporting normal progress. `ArchiveRestoreViewModel` avoids
 * it by construction — it reads the breadcrumb once, in `init`, so a restore's own breadcrumb never
 * appears on the screen writing it — and this is the same guarantee for every other reader.
 *
 * The suppression is per package, not global: an interrupted restore of A is still worth reporting
 * while a restore of B runs, and those are different rows in the notice's own history.
 *
 * Cost of the filter: the notice waits for [ArchiveJobLauncher.runningJobFor] to answer before it can
 * be shown, which is a frame or two after the breadcrumb is known. A notice that appears slightly late
 * is the right trade against one that is wrong while it is early.
 */
@Factory
class ObserveInterruptedRestoreUseCase(
    private val breadcrumbs: ArchiveBreadcrumbStore,
    private val launcher: ArchiveJobLauncher,
) {

    /**
     * Emits on every breadcrumb change and on every change to that app's live-job state. Null means
     * "nothing to report", which covers both "no breadcrumb" and "the restore it belongs to has not
     * finished yet" — [ArchiveJobLauncher.runningJobFor] matches on `!isFinished`, so a restore still
     * sitting in the queue suppresses the notice exactly as a running one does. That is the behaviour
     * this wants: a queued restore has not failed either.
     */
    // `flatMapLatest` because the inner flow's *key* comes from the outer value: a new breadcrumb names
    // a different package, and the previous package's job watcher has to stop feeding this one.
    // Experimental in kotlinx.coroutines 1.11 and opted into deliberately, as in `BackupRunner`.
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<ArchiveBreadcrumb?> =
        breadcrumbs.observe().flatMapLatest { crumb ->
            if (crumb == null) {
                // Not `launcher.runningJobFor(...)` with a null crumb folded in afterwards: there is no
                // package to ask about, and a flow that never emits would strand the collector on its
                // initial value instead of taking a dismissed notice down.
                flowOf(null)
            } else {
                launcher.runningJobFor(ThorJobKind.ARCHIVE_RESTORE, crumb.packageName)
                    .map { liveJobId -> crumb.takeIf { liveJobId == null } }
            }
        }
}
