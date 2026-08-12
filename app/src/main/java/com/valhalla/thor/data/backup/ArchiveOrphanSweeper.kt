// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.data.repository.UriArchiveSourceFactory
import com.valhalla.thor.domain.model.AppDataArchiveStagingDir
import com.valhalla.thor.domain.model.ArchiveBundleCacheDir
import com.valhalla.thor.domain.model.ObbExportStagingDir
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.util.Logger
import java.io.File

/**
 * §10's launch sweep, plus §8.5's interruption report.
 *
 * Five things get cleaned, by three different rules:
 * 1. **Thor's own staging directory** under `cacheDir` — emptied wholesale. Nothing but staged tars
 *    and staged bundles is ever written there, and the Odin shell dies with the process, so anything
 *    surviving a restart is garbage.
 * 2. **The bundle build tree** `ArchiveBackupWorker` hands to `AppBundleBuilder` — also emptied
 *    wholesale, and for the same reason. Not in the plan's list of three; see the class KDoc on
 *    [ArchiveBundleCacheDir]. The worker deletes only the `.xapk` it was handed back, so a kill
 *    between "split copied" and "bundle returned" strands a whole app's APKs.
 * 3. **The staged expansion files** under `externalCacheDir` — the other half of the same killed
 *    build, and for a large game the *bigger* half. They are not under `cacheDir` at all (see
 *    [ObbExportStagingDir]), so target 2 does not reach them.
 * 4. **The read-copies** [UriArchiveSourceFactory] may leave in `cacheDir` — matched by
 *    [UriArchiveSourceFactory.Companion.isReadCopyName]. Not by exact name: the factory now names one
 *    per `open` with `createTempFile`, so an exact-equality sweep matches only the legacy name and
 *    walks past every multi-gigabyte copy a current build stranded. `cacheDir` itself is shared with
 *    Coil, Room and the bundle builder, so the predicate is deliberately narrow rather than a
 *    wildcard — it is prefix-and-suffix on a spelling nothing else in this app writes, and every
 *    other entry in that directory is left alone.
 * 5. **`.part` containers in the user's folder** — only the names [PartialArchiveLedger] recorded, and
 *    a name is forgotten only once the file is gone.
 *
 * Resist reading that list as "and that is everything this feature can leak". It is not: a killed
 * **restore** strands the same order of magnitude in `externalCacheDir/obb_in/<pkg>`
 * (`OBB_INSTALL_STAGING_DIR`, a file-level `internal const val` in `ObbInstaller.kt` — not a member
 * of the class, which is what the pointer here used to say), which is not a target here. That is
 * deliberate rather
 * than overlooked. `obb_in` is shared with the already-shipped portable installer, so a wholesale
 * delete at launch would reach a subtree that path may be using; and `ObbInstaller` opens each
 * placement by deleting `obb_in/<pkg>` — **that package's subtree only, not the tree** — so a
 * strand clears when the *same* package is restored again, and not before. Recorded for the
 * whole-branch review.
 *
 * What it does **not** do is clear the breadcrumb. It reports it. Clearing it here would make the
 * sweep the thing that silences the warning a user is owed.
 *
 * **Not `@Single`-annotated**: it takes a `File`, which the Koin compiler plugin cannot resolve and
 * `compileSafety` turns into a build failure. Bound by a `@Single` function in `di/Modules.kt`.
 *
 * @param externalCacheDir nullable, because `Context.getExternalCacheDir()` is: external storage can
 *   be unmounted or unavailable at launch. Null means target 3 is skipped — the builder could not have
 *   staged anything there either, since it reads the same nullable value.
 */
class ArchiveOrphanSweeper(
    private val ledger: PartialArchiveLedger,
    private val archiveStore: AppArchiveStore,
    private val breadcrumbs: ArchiveBreadcrumbStore,
    private val cacheDir: File,
    private val externalCacheDir: File?,
) {

    data class SweepReport(
        /** Non-null when a restore was in flight when Thor last stopped. §8.5. */
        val interrupted: ArchiveBreadcrumb?,
        val containersRemoved: Int,
        val stagedFilesRemoved: Int,
    )

    suspend fun sweep(): SweepReport {
        val staged = sweepStaging() + sweepBundleCache() + sweepObbStaging() + sweepReadCopy()
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

    /**
     * The other half of a killed bundle build, and the half [sweepBundleCache] cannot reach.
     *
     * `AppBundleBuilderImpl` stages expansion files in `externalCacheDir/obb_out/<pkg>` — outside
     * `cacheDir`, because the privileged shell that copies them out of `Android/obb/<pkg>/` cannot
     * write into `/data/data/<thor>` (0700). It deletes that subtree on success and on both failure
     * paths, so the only way one survives is a process kill mid-build, which is the case this sweep
     * exists for. For an OBB game those files are gigabytes.
     *
     * The whole `obb_out` directory, not one package's subtree: the sweep does not know which package
     * was in flight, nothing else writes there, and any export — archive or share — that was still
     * using it died with the process. Counted as one for the same reason as [sweepBundleCache].
     *
     * Null [externalCacheDir] is the ordinary "external storage not available" case, not an error:
     * the builder reads the same nullable value and stages nothing when it is null.
     */
    private fun sweepObbStaging(): Int {
        val dir = File(externalCacheDir ?: return 0, ObbExportStagingDir.NAME)
        return if (dir.exists() && dir.deleteRecursively()) 1 else 0
    }

    /**
     * Every read-copy in `cacheDir`, not one fixed name.
     *
     * [UriArchiveSourceFactory] names its fallback copy per `open` so two overlapping opens cannot
     * truncate each other's zip. The name is therefore unknown here, and matching it by equality —
     * which is what this did — swept the legacy name and nothing else, leaving a copy the size of the
     * whole archive behind after every killed restore.
     *
     * The listing is `cacheDir`'s own children only. It does not recurse, and
     * [UriArchiveSourceFactory.Companion.isReadCopyName] decides every deletion, so Coil's and Room's
     * directories are walked past rather than opened.
     */
    private fun sweepReadCopy(): Int {
        val children = cacheDir.listFiles() ?: return 0
        // `&&` short-circuits, so `delete()` is reached only for a name the predicate claimed.
        return children.count { UriArchiveSourceFactory.isReadCopyName(it.name) && it.delete() }
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
