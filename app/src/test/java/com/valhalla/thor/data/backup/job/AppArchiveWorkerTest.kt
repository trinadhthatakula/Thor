// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reporting decisions `ArchiveRestoreWorker` makes, pinned where they can be.
 *
 * The worker itself is a `CoroutineWorker` and there is no Robolectric and no `work-testing` on this
 * module's test classpath, so nothing that constructs one can run here. That is exactly why the four
 * functions under test are top-level: every judgement about *what the user is told* lives outside the
 * class, and only the WorkManager wiring — which no JVM test could cover either way — is inside it.
 * The fourth, [boundedForJobData], lives in `ThorJobWorker.kt` for the same reason and is covered here
 * because this is where the reporting decisions are pinned.
 */
class AppArchiveWorkerTest {

    // Real, not a fake: PBKDF2 and HMAC are JCE, so the check under test is the shipped one. Four
    // rounds keeps it instant; `AppArchiveCipherTest` pins the shipped count.
    private val cipher = AppArchiveCipher()

    @Test
    fun `archive restore diagnostics do not include the user-selected URI`() {
        val source = workerSource()

        assertTrue(
            "archive restore diagnostics expose the selected URI",
            !source.contains("uri=" + '$' + "{request.uriString}"),
        )
    }

    @Test
    fun `archive worker context carries its lane package work id and semantic class`() {
        val workRequestId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val commandClass = PrivilegeCommandClass("archive.backup")

        val execution = archiveExecutionContext(
            commandClass = commandClass,
            packageName = "com.example.target",
            workRequestId = workRequestId,
        )

        assertEquals(PrivilegeExecutionLane.ARCHIVE, execution.lane)
        assertEquals(commandClass, execution.commandClass)
        assertEquals("com.example.target", execution.packageName)
        assertSame(workRequestId, execution.workRequestId)
        assertNull(execution.sweepRequestId)
        assertNull(execution.commandTimeout)
    }

    // region wrongKeyReason

    @Test
    fun `the key the archive's own passphrase derives opens it`() {
        val key = cipher.deriveKey(PASSPHRASE.toCharArray(), SALT, iterations = 4)

        assertNull(wrongKeyReason(header(key), key, cipher))
    }

    @Test
    fun `a key derived at a different round count is refused before anything is decrypted`() {
        // The case the retired KDF-count comparison was a proxy for, asserted through the mechanism
        // that replaced it. Without this the run reaches the first `decryptMember`, fails its GCM tag,
        // and reports a damaged archive — which is false, and points the user at their file.
        val archiveKey = cipher.deriveKey(PASSPHRASE.toCharArray(), SALT, iterations = 4)
        val jobKey = cipher.deriveKey(PASSPHRASE.toCharArray(), SALT, iterations = 5)

        assertEquals(
            "this backup could not be opened with the passphrase this restore was started with — " +
                    "open the file again and unlock it",
            wrongKeyReason(header(archiveKey), jobKey, cipher),
        )
    }

    @Test
    fun `a key derived from a different salt is refused too`() {
        // The count check could not see this one at all: same rounds, same passphrase, different
        // archive. It is what a `content://` URI whose document was swapped between the confirm screen
        // and the job produces, and the package-name check above only catches the swap to *another*
        // app's backup.
        val archiveKey = cipher.deriveKey(PASSPHRASE.toCharArray(), SALT, iterations = 4)
        val jobKey = cipher.deriveKey(PASSPHRASE.toCharArray(), ByteArray(16) { 9 }, iterations = 4)

        assertTrue(wrongKeyReason(header(archiveKey), jobKey, cipher) != null)
    }

    @Test
    fun `an archive written at a count this build no longer uses is restorable`() {
        // The regression the port exists to prevent. `KDF_ITERATIONS` is this build's number; an
        // archive from a Thor whose number differed is opened by the key its *own* header describes,
        // and this function must not stand in the way of that. The retired comparison refused it.
        val key = cipher.deriveKey(PASSPHRASE.toCharArray(), SALT, iterations = KDF_ITERATIONS - 1)

        assertNull(wrongKeyReason(header(key, iterations = KDF_ITERATIONS - 1), key, cipher))
    }

    @Test
    fun `a verifier that is not Base64 is reported as a damaged header, not as a wrong passphrase`() {
        // Two different things to do about them: one sends the user back to the file, the other says
        // the file is not readable. Collapsing them would have a user retyping a correct passphrase.
        val key = cipher.deriveKey(PASSPHRASE.toCharArray(), SALT, iterations = 4)

        assertEquals(
            "this backup's header could not be read well enough to check the passphrase",
            wrongKeyReason(header(key).copy(verifier = "not base64!!"), key, cipher),
        )
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

    // region refusalReason

    /**
     * The defect itself: the worker concatenated the enum, so the sentence a user read ended in
     * `SIGNER_MISMATCH`.
     *
     * Asserted as "no arm may contain its own constant name" rather than one spot check, because the
     * regression is per-arm — a tenth arm added as `refusal.name`, or one arm left as a `TODO`, is the
     * same defect back in a place a single-arm assertion does not look. `values()` grows with the enum;
     * a hand-listed set would not.
     */
    @Test
    fun `no refusal reports itself as a Kotlin identifier`() {
        ArchiveRestoreRefusal.entries.forEach { refusal ->
            val reason = refusalReason(refusal)

            assertTrue(
                "${refusal.name} is reported as its own constant: $reason",
                !reason.contains(refusal.name),
            )
            assertTrue("${refusal.name} has no sentence", reason.isNotBlank())
        }
    }

    /**
     * Nine arms, nine different sentences.
     *
     * Distinctness is the property with teeth. A `when` stays exhaustive — the compiler sees to that —
     * while a new arm is bolted onto an existing one's branch, and the result is a user told the wrong
     * reason rather than an untranslated one, which is worse. `SIGNER_MISMATCH` and
     * `SIGNER_UNVERIFIABLE` are the pair that invites it: both are about a signature, and only one of
     * them means the backup belongs to someone else.
     */
    @Test
    fun `each refusal has its own sentence`() {
        val reasons = ArchiveRestoreRefusal.entries.map(::refusalReason)

        assertEquals(ArchiveRestoreRefusal.entries.size, reasons.toSet().size)
    }

    /**
     * The three arms that can actually be reached.
     *
     * `ArchiveRestoreUiState.canStart` requires `refusal == null` from this same gate over this same
     * header and class set, so a user cannot press Restore into a refusal. What the re-gate inside the
     * worker exists to catch is the app changing underneath the job — updated, replaced, or removed
     * while it waited in the chain — and those are the three below. They are named individually
     * because they are the ones a user will really see, and the loop above would let all three say
     * "this backup cannot be restored" and still pass.
     */
    @Test
    fun `the three reachable refusals say which of them happened`() {
        assertTrue(
            refusalReason(ArchiveRestoreRefusal.SIGNER_MISMATCH).contains("different developer")
        )
        assertTrue(
            refusalReason(ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE)
                .contains("signature could not be read")
        )
        assertTrue(
            refusalReason(ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT)
                .contains("no longer installed")
        )
    }

    /**
     * Reads as the second half of the worker's sentence, not as a fragment.
     *
     * The call site is `"this backup can no longer be restored: " + refusalReason(...)`, and that is
     * the whole of what the user sees. An arm written as a standalone sentence — leading capital,
     * trailing full stop, the way the screen's `restore_refused_*` strings are correctly written for
     * *their* surface — produces "this backup can no longer be restored: Nothing is selected." here.
     * The two surfaces need different casing for the same fact, which is the concrete reason this
     * function is not the screen's map reused.
     */
    @Test
    fun `every sentence is a clause, not a standalone sentence`() {
        ArchiveRestoreRefusal.entries.forEach { refusal ->
            val reason = refusalReason(refusal)

            assertEquals(
                "${refusal.name} starts with a capital: $reason",
                reason.first().lowercaseChar(),
                reason.first(),
            )
            assertTrue("${refusal.name} ends with a full stop: $reason", !reason.endsWith("."))
        }
    }

    // endregion

    // region boundedForJobData

    @Test
    fun `a sentence short enough to send is returned untouched`() {
        // Identity, not merely equality: nothing on the ordinary path should allocate, and every
        // reason this feature actually produces is an order of magnitude under the bound.
        val reason = "the backup could not be written to the folder you chose"
        assertSame(reason, reason.boundedForJobData())
        assertSame("", "".boundedForJobData())

        val longest = "x".repeat(MAX_JOB_MESSAGE_CHARS)
        assertSame(longest, longest.boundedForJobData())
    }

    @Test
    fun `a sentence too long to send is cut to the bound, marked, and never grows`() {
        // The reason the bound exists: `Data.Builder.build()` throws above `Data.MAX_DATA_BYTES`
        // rather than truncating, and on the restore-success path that throw turns a restore that
        // already finished into one reported as failed. The assertion that matters is the *ceiling* —
        // a marker appended past the limit would defeat the whole point.
        val huge = "y".repeat(10_000)
        val bounded = huge.boundedForJobData()

        assertEquals(MAX_JOB_MESSAGE_CHARS, bounded.length)
        assertTrue(bounded.endsWith("…"))
        assertTrue(bounded.startsWith("yyy"))

        // One over, which is the only interesting input near the edge.
        val justOver = "z".repeat(MAX_JOB_MESSAGE_CHARS + 1)
        assertEquals(MAX_JOB_MESSAGE_CHARS, justOver.boundedForJobData().length)
    }

    @Test
    fun `four bounded warnings stay well inside what Data will accept`() {
        // The arithmetic the bound is chosen for. `RestoreAppArchiveUseCase` produces at most three
        // warnings plus an OBB notice, and `Data` refuses a payload over 10,240 bytes *in total* —
        // keys, values and framing. This is the headroom claim in `boundedForJobData`'s own KDoc,
        // pinned so a later raise of the bound has to face it.
        val worst = List(4) { "w".repeat(10_000).boundedForJobData() }

        assertEquals(4 * MAX_JOB_MESSAGE_CHARS, worst.sumOf { it.length })
        assertTrue(worst.sumOf { it.length } * 2 < 10_240)
    }

    // endregion

    /**
     * A header carrying the verifier for [key], which is the only field [wrongKeyReason] reads.
     *
     * `iterations` is a parameter rather than a constant so the "a count this build no longer uses"
     * test can state the case it is about; nothing in the function under test looks at it any more,
     * and that is precisely what that test pins.
     */
    private fun header(key: SecretKey, iterations: Int = 4) = ArchiveHeader(
        createdAt = 1_000L,
        thorVersionCode = 1950,
        packageName = "com.example.app",
        versionCode = 100L,
        userId = 0,
        signerSha256 = "AB".repeat(32),
        kdf = ArchiveKdf(
            iterations = iterations,
            salt = Base64.getEncoder().encodeToString(SALT),
        ),
        verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
        members = emptyList(),
    )

    private fun workerSource(): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val source = File(
                directory,
                "app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt",
            )
            if (source.isFile) return source.readText()
            directory = directory.parentFile
        }
        error("could not locate AppArchiveWorker.kt from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PASSPHRASE = "hunter2"
        val SALT = ByteArray(16) { it.toByte() }
    }
}
