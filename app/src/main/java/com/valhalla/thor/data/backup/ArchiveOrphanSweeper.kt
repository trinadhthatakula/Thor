// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.data.repository.UriArchiveSourceFactory
import com.valhalla.thor.domain.model.AppDataArchiveStagingDir
import com.valhalla.thor.domain.model.ArchiveBundleCacheDir
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.util.Logger
import java.io.File

/**
 * §10's launch sweep, plus §8.5's interruption report.
 *
 * Four things get cleaned, by three different rules:
 * 1. **Thor's own staging directory** under `cacheDir` — emptied wholesale. Nothing but staged tars
 *    and staged bundles is ever written there, and the Odin shell dies with the process, so anything
 *    surviving a restart is garbage.
 * 2. **The bundle build tree** `ArchiveBackupWorker` hands to `AppBundleBuilder` — also emptied
 *    wholesale, and for the same reason. Not in the plan's list of three; see the class KDoc on
 *    [ArchiveBundleCacheDir]. The worker deletes only the `.xapk` it was handed back, so a kill
 *    between "split copied" and "bundle returned" strands a whole app's APKs, which for a large game
 *    is the single biggest thing this feature can leak.
 * 3. **The read-copy** [UriArchiveSourceFactory] may leave in `cacheDir` — deleted by its exact name.
 *    `cacheDir` itself is shared with Coil, Room and the bundle builder; a pattern sweep there would
 *    delete another subsystem's working set.
 * 4. **`.part` containers in the user's folder** — only the names [PartialArchiveLedger] recorded, and
 *    a name is forgotten only once the file is gone.
 *
 * What it does **not** do is clear the breadcrumb. It reports it. Clearing it here would make the
 * sweep the thing that silences the warning a user is owed.
 *
 * **Not `@Single`-annotated**: it takes a `File`, which the Koin compiler plugin cannot resolve and
 * `compileSafety` turns into a build failure. Bound by a `@Single` function in `di/Modules.kt`.
 */
class ArchiveOrphanSweeper(
    private val ledger: PartialArchiveLedger,
    private val archiveStore: AppArchiveStore,
    private val breadcrumbs: ArchiveBreadcrumbStore,
    private val cacheDir: File,
) {

    data class SweepReport(
        /** Non-null when a restore was in flight when Thor last stopped. §8.5. */
        val interrupted: ArchiveBreadcrumb?,
        val containersRemoved: Int,
        val stagedFilesRemoved: Int,
    )

    suspend fun sweep(): SweepReport {
        val staged = sweepStaging() + sweepBundleCache() + sweepReadCopy()
        val containers = sweepContainers()
        val interrupted = breadcrumbs.read()
        if (interrupted != null) {
            Logger.w(TAG, "a restore of ${interrupted.packageName} did not finish")
        }
        if (staged > 0 || containers > 0) {
            Logger.w(TAG, "swept $staged staged files and $containers partial containers")
        }
        return SweepReport(interrupted, containers, staged)
    }

    /** The directory survives; only its contents go. See the test that pins this. */
    private fun sweepStaging(): Int {
        val staging = File(cacheDir, AppDataArchiveStagingDir.NAME)
        val children = staging.listFiles() ?: return 0
        return children.count { it.deleteRecursively() }
    }

    /**
     * The whole directory, not just its contents — unlike [sweepStaging].
     *
     * Nothing recreates it on a `mkdirs()`-per-call basis the way the gateway's `stagingFile` does;
     * `AppBundleBuilderImpl.build` creates it when it needs it. Counted as one, because "how many
     * files were inside the tree a dead backup left" is not a number anyone reads and computing it
     * means walking a tree that is about to be deleted anyway.
     */
    private fun sweepBundleCache(): Int {
        val dir = File(cacheDir, ArchiveBundleCacheDir.NAME)
        return if (dir.exists() && dir.deleteRecursively()) 1 else 0
    }

    private fun sweepReadCopy(): Int {
        val copy = File(cacheDir, UriArchiveSourceFactory.COPY_FILE_NAME)
        return if (copy.exists() && copy.delete()) 1 else 0
    }

    /**
     * The empty-ledger short-circuit is load-bearing, not an optimisation: this runs on **every**
     * launch, and the common case is that nothing was interrupted. Without it, every cold start
     * resolves the SAF tree and queries a provider to delete nothing.
     */
    private suspend fun sweepContainers(): Int {
        val names = ledger.names()
        if (names.isEmpty()) return 0
        val removed = archiveStore.discardOrphans(names)
        removed.forEach { ledger.forget(it) }
        return removed.size
    }

    private companion object {
        const val TAG = "ArchiveOrphanSweeper"
    }
}
