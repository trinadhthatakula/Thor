// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reporting decisions `ArchiveRestoreWorker` makes, pinned where they can be.
 *
 * The worker itself is a `CoroutineWorker` and there is no Robolectric and no `work-testing` on this
 * module's test classpath, so nothing that constructs one can run here. That is exactly why the three
 * functions under test are top-level: every judgement about *what the user is told* lives outside the
 * class, and only the WorkManager wiring — which no JVM test could cover either way — is inside it.
 */
class AppArchiveWorkerTest {

    // region unsupportedKdfReason

    @Test
    fun `an archive written at this build's iteration count is restorable`() {
        // The only count `BackupAppArchiveUseCase` ever stamps, and the count
        // `ThorJobLauncher.startRestore` derives at. Every archive this build wrote takes this arm.
        assertNull(unsupportedKdfReason(KDF_ITERATIONS))
    }

    @Test
    fun `an archive written at another count is refused before anything is decrypted`() {
        // The key the worker holds was derived at KDF_ITERATIONS, so this archive's members cannot be
        // opened with it. Without this the run reaches the first `decryptMember`, fails its GCM tag,
        // and reports a damaged archive — which is false, and points the user at their file.
        val reason = unsupportedKdfReason(100_000)

        assertEquals(
            "this backup was written with a different key setting (100000 rounds, not " +
                "$KDF_ITERATIONS), and this version of Thor cannot restore it",
            reason,
        )
    }

    @Test
    fun `a lower count is refused too, not only a higher one`() {
        // Both directions are the same bug: the derived key differs, and which side of today's
        // constant the archive sits on has no bearing on that.
        assertTrue(unsupportedKdfReason(KDF_ITERATIONS - 1) != null)
        assertTrue(unsupportedKdfReason(KDF_ITERATIONS + 1) != null)
    }

    @Test
    fun `the refusal names the count the archive wants`() {
        // Asserted separately from the sentence above because it is the part with a job: a bug report
        // that carries the number identifies which Thor wrote the archive. A message that only said
        // "a different version" would pass a type-shaped assertion and tell nobody anything.
        assertTrue(unsupportedKdfReason(310_000)!!.contains("310000"))
    }

    // endregion

    // region restoreFailureReason

    @Test
    fun `a failure that replaced nothing reports only its reason`() {
        val reason = restoreFailureReason(
            ArchiveRestoreOutcome.Failed("Thor could not read the app's user id", emptyList())
        )

        assertEquals("Thor could not read the app's user id", reason)
    }

    @Test
    fun `a failure after some classes landed names them`() {
        val reason = restoreFailureReason(
            ArchiveRestoreOutcome.Failed(
                "this archive has no ext-data data",
                listOf(DataClass.CE, DataClass.DE),
            )
        )

        assertEquals("this archive has no ext-data data (ce, de was already replaced)", reason)
    }

    /**
     * Obligation C. `RestoreAppArchiveUseCase` populates `classPossiblyCleared` on a `SwapFailed` and
     * nothing read it, so the one failure that may have destroyed data reported the same sentence as
     * the failures that changed nothing.
     */
    @Test
    fun `a swap that may have emptied a class says so`() {
        val reason = restoreFailureReason(
            ArchiveRestoreOutcome.Failed(
                reason = "the staged data could not be moved into place",
                classesRestored = emptyList(),
                classPossiblyCleared = DataClass.CE,
            )
        )

        assertEquals(
            "the staged data could not be moved into place. Thor could not tell whether ce was " +
                "left as it was or emptied, so check the app before you use it",
            reason,
        )
    }

    @Test
    fun `a swap failure after other classes landed reports both facts`() {
        // The two clauses are independent and both matter: `de` is holding the archive's copy, and
        // `ce` may be holding nothing at all.
        val reason = restoreFailureReason(
            ArchiveRestoreOutcome.Failed(
                reason = "the staged data could not be moved into place",
                classesRestored = listOf(DataClass.DE),
                classPossiblyCleared = DataClass.CE,
            )
        )

        assertTrue("the landed class is missing", reason.contains("de was already replaced"))
        assertTrue("the cleared class is missing", reason.contains("whether ce was"))
    }

    @Test
    fun `a failure with no possibly-cleared class says nothing about emptying`() {
        // The hedge is only true for `SwapFailed`. Every other failure leaves the class it stopped on
        // exactly as it was, and telling those users to check their app for missing data would be a
        // false alarm on the common path.
        val reason = restoreFailureReason(
            ArchiveRestoreOutcome.Failed("this archive has no ce data", listOf(DataClass.DE))
        )

        assertTrue(reason, !reason.contains("emptied"))
    }

    // endregion

    // region obbNotice

    @Test
    fun `no placement is nothing to report`() {
        // An install-first restore, a data-only archive, or game data the user did not ask for.
        assertNull(obbNotice(null))
    }

    @Test
    fun `a failed placement is not reported twice`() {
        // `RestoreAppArchiveUseCase` already put "the game data could not be placed: <reason>" into
        // `warnings`. A sentence here would render the same failure twice on one screen.
        assertNull(obbNotice(ObbPlacement.Failed("shared storage is unavailable")))
    }

    @Test
    fun `a successful placement is not filed under things that did not finish`() {
        // The screen renders this array under `restore_done_warnings` — "Some parts did not finish:".
        // The count is logged at the call site instead.
        assertNull(obbNotice(ObbPlacement.Placed(3)))
    }

    /**
     * Obligation E's whole point. The user ticked "restore game data", the archive carried an
     * installer, and that installer declared no expansion files — so `placeBundleObb` returned
     * `NotNeeded` and, before this, the checkbox silently did nothing.
     */
    @Test
    fun `an installer with no game data in it is reported`() {
        assertEquals(
            "the app installer in this backup carries no game data, so none was placed",
            obbNotice(ObbPlacement.NotNeeded),
        )
    }

    // endregion
}
