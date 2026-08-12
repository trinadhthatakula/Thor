// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.jobKindFromId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The handoff a job notification's tap goes through, and the two decisions it can get wrong: reopening
 * a sheet on the wrong job, and reopening one on a job that has already ended.
 *
 * Testable on a plain JVM because [JobSheetTargets] deliberately holds no Android type — the thing
 * that *does* need a runtime is the trampoline activity around it, which is why this class exists
 * separately from it rather than as a field on the activity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSheetTargetsTest {

    /** Stands in for a `WorkSpec` id. Fixed rather than random so a failure names the same job twice. */
    private fun jobId(n: Int) = UUID(0L, n.toLong())

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
        targets.set(jobId(1), JobSheetTarget.Backup("com.a", "App A"))

        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP))
        assertEquals(JobSheetTarget.Backup("com.a", "App A"), targets.requests.first())
    }

    @Test
    fun `the two kinds do not see each other`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Restore("content://docs/1"))

        // A backup and a restore share one chain but not one notification: each kind has its own id
        // and its own PendingIntent, so a tap on one must never resolve to the other's target.
        assertFalse(targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP))
        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
    }

    @Test
    fun `a request survives having no collector`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Restore("content://docs/1"))

        // The tap is what starts the UI, so this is the ordinary ordering, not a race. CONFLATED
        // buffers it; the `first()` below is the collector arriving afterwards.
        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))

        assertEquals(JobSheetTarget.Restore("content://docs/1"), targets.requests.first())
    }

    @Test
    fun `two taps before anyone collects leave the latest, not a queue of sheets`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Backup("com.a", "App A"))
        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)
        targets.set(jobId(2), JobSheetTarget.Backup("com.b", "App B"))
        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)

        // Conflated on purpose: the alternative is opening one sheet per impatient tap.
        assertEquals(JobSheetTarget.Backup("com.b", "App B"), targets.requests.first())
    }

    @Test
    fun `retargeting replaces the label a tap will show`() = runTest {
        val targets = JobSheetTargets()
        // What the worker can publish before `setForeground`: the package name in both fields.
        targets.set(jobId(1), JobSheetTarget.Backup("com.supercell.clashofclans", "com.supercell.clashofclans"))
        // What it publishes once the AppInfo lookup has returned. Same job, so this is an update.
        targets.set(jobId(1), JobSheetTarget.Backup("com.supercell.clashofclans", "Clash of Clans"))

        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)

        assertEquals(
            JobSheetTarget.Backup("com.supercell.clashofclans", "Clash of Clans"),
            targets.requests.first()
        )
    }

    @Test
    fun `a tap between the two publishes still shows the resolved label`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Backup("com.supercell.clashofclans", "com.supercell.clashofclans"))

        // The tap lands in the window before the AppInfo lookup returns...
        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)
        // ...and the worker resolves the label before the UI is up to collect.
        targets.set(jobId(1), JobSheetTarget.Backup("com.supercell.clashofclans", "Clash of Clans"))

        // Resolved at delivery, not at the tap. Buffering the target instead of the kind would title
        // the sheet with an application id for a job whose label was known by the time it opened.
        assertEquals(
            JobSheetTarget.Backup("com.supercell.clashofclans", "Clash of Clans"),
            targets.requests.first()
        )
    }

    @Test
    fun `a finished job clears its own target`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Restore("content://docs/1"))

        targets.clear(ThorJobKind.ARCHIVE_RESTORE, jobId(1))

        // The notification is gone by now, so a target left behind would be reopened by the *next*
        // restore's notification and show the wrong archive.
        assertFalse(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
    }

    @Test
    fun `a finishing job does not clear its successor's target`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Restore("content://docs/1"))

        // The successor publishes as it starts...
        val second = JobSheetTarget.Restore("content://docs/2")
        targets.set(jobId(2), second)
        // ...and only then does the previous worker's `finally` run. Jobs of one kind serialise
        // through THOR_JOB_CHAIN, but APPEND_OR_REPLACE and cancellation both make this ordering
        // reachable, and an unconditional clear(kind) here would strand the running job's notification.
        targets.clear(ThorJobKind.ARCHIVE_RESTORE, jobId(1))

        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
        assertEquals(second, targets.requests.first())
    }

    @Test
    fun `a finishing job does not clear a successor whose target is identical`() = runTest {
        val targets = JobSheetTargets()
        // Cancel a restore and immediately restart the same archive — or cancel a backup and retry the
        // same app. Both workers then hold an EQUAL target, because JobSheetTarget is a data class.
        val same = JobSheetTarget.Restore("content://docs/1")
        targets.set(jobId(1), same)
        targets.set(jobId(2), same)

        targets.clear(ThorJobKind.ARCHIVE_RESTORE, jobId(1))

        // This is why the slot is keyed on the job id and not on the target's value. A compare-and-
        // remove on the target alone matches here, erases the running job's entry, and every tap on
        // its notification for the rest of the job resumes the app with no sheet.
        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
        assertEquals(same, targets.requests.first())
    }

    @Test
    fun `a worker that never published clears nothing`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Backup("com.a", "App A"))

        // `ThorJobWorker`'s finally calls clear() unconditionally, including for a job whose input
        // Data carried no package name to publish. It must not take the live entry with it.
        targets.clear(ThorJobKind.ARCHIVE_BACKUP, jobId(99))

        assertTrue(targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP))
    }

    @Test
    fun `a tap on a job that ends before the UI collects opens nothing`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Restore("content://docs/1"))

        // App lock is on: the trampoline publishes the request minutes before `MainScreen` composes.
        targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE)
        // The restore finishes while the user is still at the biometric prompt, or never unlocks and
        // comes back later in the same process.
        targets.clear(ThorJobKind.ARCHIVE_RESTORE, jobId(1))

        val seen = mutableListOf<JobSheetTarget>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            targets.requests.toList(seen)
        }

        // Liveness is re-checked at delivery. Buffering the target itself would replay a dead job to
        // whatever view model is built next — a sheet the user did not ask for, on a SAF URI whose
        // grant died with the task that received it, so it could only fail to read its own archive.
        assertTrue(seen.isEmpty())
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
