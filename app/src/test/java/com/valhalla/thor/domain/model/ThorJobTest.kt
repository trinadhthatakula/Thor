// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

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
