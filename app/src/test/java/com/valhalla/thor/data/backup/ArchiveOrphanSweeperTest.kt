// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.data.repository.UriArchiveSourceFactory
import com.valhalla.thor.domain.model.AppDataArchiveStagingDir
import com.valhalla.thor.domain.model.ArchiveBundleCacheDir
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveDestination
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveOrphanSweeperTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeStore(private val removable: Set<String>) : AppArchiveStore {
        var asked: Set<String> = emptySet()

        /** Distinguishes "never called" from "called with nothing" — `asked` alone cannot. */
        var callCount: Int = 0

        override suspend fun openArchive(fileName: String): ArchiveDestination? = null
        override suspend fun currentTargetLabel(): String = "Downloads/Thor"
        override suspend fun discardOrphans(names: Set<String>): Set<String> {
            callCount++
            asked = names
            return names intersect removable
        }
    }

    private class FakeBreadcrumbs(private var crumb: ArchiveBreadcrumb?) : ArchiveBreadcrumbStore {
        var cleared = false

        // Boolean, not Unit: Task 14's review made the §8.5 write reportable.
        override suspend fun write(packageName: String, appLabel: String): Boolean = true
        override suspend fun read(): ArchiveBreadcrumb? = crumb
        override suspend fun clear() {
            cleared = true
            crumb = null
        }
    }

    private fun cacheWith(vararg staged: String): Pair<File, File> {
        val cache = temp.newFolder("cache")
        val staging = File(cache, AppDataArchiveStagingDir.NAME).apply { mkdirs() }
        staged.forEach { File(staging, it).writeText("x") }
        return cache to staging
    }

    @Test
    fun `staged tars left by a dead process are deleted`() = runTest {
        val (cache, staging) = cacheWith("ce.tar", "restore-de.tar", "app.xapk")
        val sweeper = ArchiveOrphanSweeper(
            ledger = PartialArchiveLedger(temp.newFolder("files1")),
            archiveStore = FakeStore(emptySet()),
            breadcrumbs = FakeBreadcrumbs(null),
            cacheDir = cache,
        )

        val report = sweeper.sweep()

        assertEquals(emptyList<File>(), staging.listFiles()?.toList() ?: emptyList<File>())
        assertEquals(3, report.stagedFilesRemoved)
    }

    @Test
    fun `the staging directory itself survives the sweep`() = runTest {
        // The gateway's `stagingFile` does `mkdirs()` on every call, so removing the directory would
        // not break anything — but a sweep that deletes a directory it was asked to empty is one
        // refactor away from being pointed at a directory it should not delete.
        val (cache, staging) = cacheWith("ce.tar")
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files2")), FakeStore(emptySet()), FakeBreadcrumbs(null), cache,
        )

        sweeper.sweep()

        assertTrue(staging.isDirectory)
    }

    @Test
    fun `the read-copy of an opened archive is deleted by its exact name`() = runTest {
        val (cache, _) = cacheWith()
        val copy = File(cache, UriArchiveSourceFactory.COPY_FILE_NAME).apply { writeText("zip") }
        val keep = File(cache, "image_cache.bin").apply { writeText("keep me") }
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files3")), FakeStore(emptySet()), FakeBreadcrumbs(null), cache,
        )

        sweeper.sweep()

        assertEquals(false, copy.exists())
        // Coil, Room and the bundle builder all keep files in cacheDir. A pattern sweep here would
        // delete another subsystem's working set.
        assertTrue(keep.exists())
    }

    @Test
    fun `only the container names the ledger recorded are offered for deletion`() = runTest {
        val (cache, _) = cacheWith()
        val ledger = PartialArchiveLedger(temp.newFolder("files4"))
        ledger.add("Thor-com.example.app-100.thorbak.part")
        val store = FakeStore(setOf("Thor-com.example.app-100.thorbak.part"))
        val sweeper = ArchiveOrphanSweeper(ledger, store, FakeBreadcrumbs(null), cache)

        val report = sweeper.sweep()

        assertEquals(setOf("Thor-com.example.app-100.thorbak.part"), store.asked)
        assertEquals(1, report.containersRemoved)
    }

    @Test
    fun `a removed container is forgotten and a surviving one is kept for the next launch`() = runTest {
        // A SAF tree on a volume that is not mounted yet fails the delete. Forgetting the name anyway
        // would leave the `.part` in the user's folder forever with nothing left that knows its name.
        val (cache, _) = cacheWith()
        val ledger = PartialArchiveLedger(temp.newFolder("files5"))
        ledger.add("gone.thorbak.part")
        ledger.add("still-there.thorbak.part")
        val sweeper = ArchiveOrphanSweeper(
            ledger, FakeStore(setOf("gone.thorbak.part")), FakeBreadcrumbs(null), cache,
        )

        sweeper.sweep()

        assertEquals(setOf("still-there.thorbak.part"), ledger.names())
    }

    @Test
    fun `an interrupted restore is reported and the breadcrumb is left for the UI`() = runTest {
        // The sweep must not be what silences the warning. Only the screen that has told the user
        // clears it — see Task 17.
        val (cache, _) = cacheWith()
        val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.app", "Example", startedAt = 5L))
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files6")), FakeStore(emptySet()), crumbs, cache,
        )

        val report = sweeper.sweep()

        assertNotNull(report.interrupted)
        assertEquals("Example", report.interrupted!!.appLabel)
        assertEquals(false, crumbs.cleared)
    }

    @Test
    fun `a clean launch sweeps nothing and reports nothing`() = runTest {
        val cache = temp.newFolder("cache-clean")
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files7")), FakeStore(emptySet()), FakeBreadcrumbs(null), cache,
        )

        val report = sweeper.sweep()

        assertEquals(ArchiveOrphanSweeper.SweepReport(null, 0, 0), report)
    }

    /**
     * Not in the brief; see the report. `ArchiveBackupWorker` builds the `.xapk` into
     * `cacheDir/archive_bundle/<pkg>/` and deletes only the file it got back, so a process killed
     * mid-backup leaves the whole app — potentially gigabytes — behind under a directory only the
     * archive backup ever writes.
     */
    @Test
    fun `the bundle build directory a killed backup left behind is deleted`() = runTest {
        val (cache, _) = cacheWith()
        val bundleDir = File(cache, "${ArchiveBundleCacheDir.NAME}/com.example.app").apply { mkdirs() }
        File(bundleDir, "app.xapk").writeText("many gigabytes, pretend")
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files8")), FakeStore(emptySet()), FakeBreadcrumbs(null), cache,
        )

        val report = sweeper.sweep()

        assertEquals(false, File(cache, ArchiveBundleCacheDir.NAME).exists())
        assertEquals(1, report.stagedFilesRemoved)
    }

    /**
     * Not in the brief. `discardOrphans` on an empty set is a SAF tree resolution and a `query` for
     * nothing; the sweep runs on every launch, so the common case — nothing was interrupted — must
     * not reach the store at all.
     */
    @Test
    fun `an empty ledger never asks the store to delete anything`() = runTest {
        val (cache, _) = cacheWith()
        val store = FakeStore(setOf("anything"))
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files9")), store, FakeBreadcrumbs(null), cache,
        )

        sweeper.sweep()

        assertEquals(0, store.callCount)
        assertEquals(emptySet<String>(), store.asked)
    }
}
