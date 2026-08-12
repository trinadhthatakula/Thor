// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.jobKindFromId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handoff a job notification's tap goes through, and the one decision it can get wrong: reopening
 * a sheet on the wrong job.
 *
 * Testable on a plain JVM because [JobSheetTargets] deliberately holds no Android type — the thing
 * that *does* need a runtime is the trampoline activity around it, which is why this class exists
 * separately from it rather than as a field on the activity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSheetTargetsTest {

    @Test
    fun `no live job means nothing is requested`() = runTest {
        val targets = JobSheetTargets()

        // The caller reads the false as "just open the app". Returning true here would leave the
        // trampoline expecting a sheet that no one is ever going to show.
        assertFalse(targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP))
    }

    @Test
    fun `a tap emits the live target for that kind`() = runTest {
        val targets = JobSheetTargets()
        targets.set(JobSheetTarget.Backup("com.a", "App A"))

        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP))
        assertEquals(JobSheetTarget.Backup("com.a", "App A"), targets.requests.first())
    }

    @Test
    fun `the two kinds do not see each other`() = runTest {
        val targets = JobSheetTargets()
        targets.set(JobSheetTarget.Restore("content://docs/1"))

        // A backup and a restore share one chain but not one notification: each kind has its own id
        // and its own PendingIntent, so a tap on one must never resolve to the other's target.
        assertFalse(targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP))
        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
    }

    @Test
    fun `a request survives having no collector`() = runTest {
        val targets = JobSheetTargets()
        targets.set(JobSheetTarget.Restore("content://docs/1"))

        // The tap is what starts the UI, so this is the ordinary ordering, not a race. CONFLATED
        // buffers it; the `first()` below is the collector arriving afterwards.
        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))

        assertEquals(JobSheetTarget.Restore("content://docs/1"), targets.requests.first())
    }

    @Test
    fun `two taps before anyone collects leave the latest, not a queue of sheets`() = runTest {
        val targets = JobSheetTargets()
        targets.set(JobSheetTarget.Backup("com.a", "App A"))
        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)
        targets.set(JobSheetTarget.Backup("com.b", "App B"))
        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)

        // Conflated on purpose: the alternative is opening one sheet per impatient tap.
        assertEquals(JobSheetTarget.Backup("com.b", "App B"), targets.requests.first())
    }

    @Test
    fun `retargeting replaces the label a tap will show`() = runTest {
        val targets = JobSheetTargets()
        // What the worker can publish before `setForeground`: the package name in both fields.
        targets.set(JobSheetTarget.Backup("com.supercell.clashofclans", "com.supercell.clashofclans"))
        // What it publishes once the AppInfo lookup has returned.
        targets.set(JobSheetTarget.Backup("com.supercell.clashofclans", "Clash of Clans"))

        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)

        assertEquals(
            JobSheetTarget.Backup("com.supercell.clashofclans", "Clash of Clans"),
            targets.requests.first()
        )
    }

    @Test
    fun `a finished job clears its own target`() = runTest {
        val targets = JobSheetTargets()
        val target = JobSheetTarget.Restore("content://docs/1")
        targets.set(target)

        targets.clearIfStill(target)

        // The notification is gone by now, so a target left behind would be reopened by the *next*
        // restore's notification and show the wrong archive.
        assertFalse(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
    }

    @Test
    fun `a finishing job does not clear its successor's target`() = runTest {
        val targets = JobSheetTargets()
        val first = JobSheetTarget.Restore("content://docs/1")
        targets.set(first)

        // The successor publishes as it starts...
        val second = JobSheetTarget.Restore("content://docs/2")
        targets.set(second)
        // ...and only then does the previous worker's `finally` run. Jobs of one kind serialise
        // through THOR_JOB_CHAIN, but APPEND_OR_REPLACE and cancellation both make this ordering
        // reachable, and a plain remove(kind) here would strand the running job's notification.
        targets.clearIfStill(first)

        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
        assertEquals(second, targets.requests.first())
    }

    @Test
    fun `a kind round-trips through the id a notification carries`() {
        // The whole contract of the intent extra: every kind must survive the trip out and back, or a
        // tap silently degrades to "open the app".
        ThorJobKind.entries.forEach { kind ->
            assertEquals(kind, jobKindFromId(kind.id))
        }
    }

    @Test
    fun `an unknown or missing id decodes to null`() {
        assertNull(jobKindFromId(null))
        assertNull(jobKindFromId(""))
        // The shape a PendingIntent from a future build might carry, and the shape `name` would give
        // if someone matched on that instead of `id`.
        assertNull(jobKindFromId("archive-export"))
        assertNull(jobKindFromId("ARCHIVE_BACKUP"))
    }
}
