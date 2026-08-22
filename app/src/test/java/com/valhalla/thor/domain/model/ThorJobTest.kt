// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.data.backup.job.ThorJobNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThorJobTest {

    @Test
    fun `the work chain name does not depend on the target`() {
        // §9.3: one chain for every archive job, so `APPEND_OR_REPLACE` *serialises* them and peak
        // disk stays one storage class however many the user starts. A per-package unique name would
        // let two multi-gigabyte captures run at once — the exact thing the format was shaped to
        // avoid.
        assertEquals(false, THOR_JOB_CHAIN.contains("com.example"))
        assertEquals(false, ThorJobKind.entries.any { THOR_JOB_CHAIN.contains(it.id) })
    }

    @Test
    fun `the sweep chain is a different name from the byte-mover chain`() {
        // Two chains is the whole point: a five-second freeze sweep must not queue behind an
        // hour-long capture. Collapsing them back to one name is a one-character mistake that would
        // show up only as "why is Thor taking so long to freeze an app", on a device, under load.
        assertNotEquals(THOR_JOB_CHAIN, THOR_SWEEP_CHAIN)
    }

    @Test
    fun `the sweep chain name does not depend on the target either`() {
        // Same reason as the byte-mover chain, minus the disk argument: sweeps serialise so two of
        // them cannot race on the same package, and because Odin's root channel is one FIFO `su`
        // session that would interleave them anyway.
        assertEquals(false, THOR_SWEEP_CHAIN.contains("com.example"))
        assertEquals(false, ThorJobKind.entries.any { THOR_SWEEP_CHAIN.contains(it.id) })
    }

    @Test
    fun `a job tag identifies the kind and the target`() {
        // Tags are how the UI answers "is this app already queued?" — the chain name cannot, because
        // every job shares it.
        assertEquals(
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
        )
        assertNotEquals(
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.other"),
        )
        assertNotEquals(
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
            jobTag(ThorJobKind.ARCHIVE_RESTORE, "com.example.game"),
        )
    }

    @Test
    fun `no kind's id is a prefix of another's`() {
        // A tag is built by concatenation, so `restore` + `:x` colliding with `restore:x` + `` would
        // silently make two jobs one.
        val ids = ThorJobKind.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        for (a in ids) for (b in ids) if (a != b) assertEquals(false, a.startsWith(b))
    }

    @Test
    fun `the ongoing and outcome notification id blocks cannot overlap`() {
        // Both blocks are BASE + kind.ordinal, so they collide the moment the enum grows past the gap
        // between them — and the symptom is that a job's outcome row is posted under the id of some
        // *other* kind's progress row, which the other job's `finally` then cancels.
        //
        // Reading two `const val`s does not load ThorJobNotifications: a const is inlined at the call
        // site, so this stays a JVM test even though the class it names needs a Context and a
        // NotificationManager. Nothing else in that file is reachable from here.
        assertEquals(
            true,
            ThorJobNotifications.BASE_RESULT_NOTIFICATION_ID - ThorJobNotifications.BASE_NOTIFICATION_ID
                    >= ThorJobKind.entries.size,
        )
    }

    @Test
    fun `each kind keeps the notification id it shipped with`() {
        // BASE + ordinal is both the notification id and the PendingIntent request code, and a
        // PendingIntent outlives the code that built it — a row the system is still showing across an
        // app update is holding whatever the *old* build wrote. So reordering this enum hands a live
        // notification the request code of a different job, and nothing about that is visible at
        // build time, in a test that only checks the block gap, or on the developer's device.
        //
        // Pinned as literal ordinals rather than as "APP_EXPORT is last", because the failure being
        // guarded is renumbering, and only the numbers state it.
        assertEquals(0, ThorJobKind.ARCHIVE_BACKUP.ordinal)
        assertEquals(1, ThorJobKind.ARCHIVE_RESTORE.ordinal)
        assertEquals(2, ThorJobKind.APP_EXPORT.ordinal)

        assertEquals(
            1102,
            ThorJobNotifications.BASE_NOTIFICATION_ID + ThorJobKind.APP_EXPORT.ordinal,
        )
        assertEquals(
            1202,
            ThorJobNotifications.BASE_RESULT_NOTIFICATION_ID + ThorJobKind.APP_EXPORT.ordinal,
        )
    }

    @Test
    fun `an unknown total reports no percentage rather than zero`() {
        // Same tri-state rule as DataClassSize and ObbProbe: "not known" is not "none". A bar pinned
        // at 0% for a job that is running reads as broken.
        val progress = ThorJobProgress(ThorJobStage.MEASURING, "Measuring", completed = 0, total = 0)

        assertNull(progress.percent)
    }

    @Test
    fun `a known total reports a percentage`() {
        val progress = ThorJobProgress(ThorJobStage.WRITING, "Writing", completed = 512, total = 2_048)

        assertEquals(25, progress.percent)
    }

    @Test
    fun `a percentage never exceeds one hundred`() {
        // `du` reports apparent size and the tar is built afterwards; the two disagree routinely, so
        // completed > total is an ordinary outcome, not a bug to assert against.
        val progress = ThorJobProgress(ThorJobStage.WRITING, "Writing", completed = 900, total = 100)

        assertEquals(100, progress.percent)
    }

    @Test
    fun `a negative completed count cannot drive the bar below zero`() {
        // Nothing should produce one, but a `du` parse and a byte counter feed this from two
        // directions and the clamp is one expression.
        val progress = ThorJobProgress(ThorJobStage.WRITING, "Writing", completed = -5, total = 100)

        assertEquals(0, progress.percent)
    }
}
