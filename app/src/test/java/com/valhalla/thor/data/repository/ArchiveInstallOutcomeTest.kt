// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.ArchiveRollbackReceipt
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict `AppArchiveInstallerImpl.installAndAwait` reaches, hoisted into three pure functions —
 * [settledArchiveInstallState] (which terminal state is the outcome), [installLanded] (did *this*
 * install put an app on the device), and [archiveInstallOutcome] (what those two mean together) — so
 * they can be checked without a `Context`.
 *
 * Same trade the rest of this task makes: `AppArchiveInstallerImpl` takes `ObbInstaller`, which needs
 * a `Context`, and there is no mocking library on this classpath — so the decision worth locking down
 * is the one that is pure, and it is locked down here rather than left to a device.
 *
 * The first of the three exists in this shape for a second reason. The bug it fixes was a **thread
 * race** — the verdict was read off a lagging collector, so the same archive produced `Installed` or
 * `InstalledWithoutGameData` depending on which thread won. A test of the racing code would be flaky
 * rather than red. Expressed as "given these two values, which is the outcome?" it is neither.
 *
 * What is actually at stake is whether restore writes someone's data into the wrong app, and whether
 * the one sentence the user will ever see about a failure is the platform's or a guess. Every case
 * below is a distinct way of getting one of those wrong.
 */
class ArchiveInstallOutcomeTest {

    private fun error(message: String = "INSTALL_FAILED_INVALID_APK") =
        InstallState.Error(UiText.DynamicString(message))

    @Test
    fun `a wait that runs out is unconfirmed, never failed`() {
        // The rule this whole outcome type exists for. `session.commit()` is fire-and-forget, so a
        // restore that actually worked can time out here; calling that `Failed` tells the user their
        // app did not install when it did, and is the bug `Unconfirmed` was added to prevent.
        assertEquals(
            ArchiveInstallOutcome.Unconfirmed,
            archiveInstallOutcome(settled = null, landed = false, reason = null),
        )
    }

    @Test
    fun `a success the platform did not stamp is unconfirmed, not installed`() {
        // The bus is process-wide. A `Success` on it that `lastUpdateTime` cannot corroborate is not
        // this install's success, and writing data into that package name is how you fill a
        // half-installed app.
        assertEquals(
            ArchiveInstallOutcome.Unconfirmed,
            archiveInstallOutcome(settled = InstallState.Success, landed = false, reason = null),
        )
    }

    @Test
    fun `a success the platform stamped is installed`() {
        assertEquals(
            ArchiveInstallOutcome.Installed,
            archiveInstallOutcome(settled = InstallState.Success, landed = true, reason = null),
        )
    }

    @Test
    fun `a failure carries the platform's own words, not a substitute sentence`() {
        // The finding this fix exists for: nobody is subscribed to `InstallerEventBus` during a
        // background restore, so the `UiText` on the settled `Error` is the ONLY place the reason
        // survives. Dhizuku's OBB refusal — "switch to root or Shizuku and try again" — is a
        // sentence a user can act on; "the app could not be installed" is not.
        assertEquals(
            ArchiveInstallOutcome.Failed("this access mode cannot reach the game data folder"),
            archiveInstallOutcome(
                settled = error(),
                landed = false,
                reason = "this access mode cannot reach the game data folder",
            ),
        )
    }

    @Test
    fun `a failure with no words of its own still says something`() {
        // A blank reason is treated exactly like a missing one: an outcome whose `reason` is "" is
        // an empty dialog, which is worse than the flat sentence it replaced.
        val fromNull = archiveInstallOutcome(settled = error(), landed = false, reason = null)
        val fromBlank = archiveInstallOutcome(settled = error(), landed = false, reason = "   ")
        assertEquals(fromNull, fromBlank)
        assertTrue((fromNull as ArchiveInstallOutcome.Failed).reason.isNotBlank())
    }

    @Test
    fun `an error after the app landed is installed-without-game-data, not failed`() {
        // The OBB half of an install: the APKs land, `InstallerRepositoryImpl` emits
        // `Success` and then `Error("… installed, but its game data could not be placed")`.
        // Reporting that as `Failed` is a factually false statement about a package that is on the
        // device, and it costs the caller the app-data restore that would still have worked.
        val reason = "Some Game installed, but its game data could not be placed: no access"
        assertEquals(
            ArchiveInstallOutcome.InstalledWithoutGameData(reason),
            archiveInstallOutcome(settled = error(), landed = true, reason = reason),
        )
    }

    @Test
    fun `the two error outcomes are told apart by the stamp, never by the message`() {
        // Text matching is the trap this pins shut: the same message, resolved from the same
        // localisable `UiText`, has to produce different outcomes purely on whether
        // `lastUpdateTime` moved. A discriminator that read the string could not do this.
        val reason = "identical wording on both paths"
        assertEquals(
            ArchiveInstallOutcome.InstalledWithoutGameData(reason),
            archiveInstallOutcome(settled = error(), landed = true, reason = reason),
        )
        assertEquals(
            ArchiveInstallOutcome.Failed(reason),
            archiveInstallOutcome(settled = error(), landed = false, reason = reason),
        )
    }

    @Test
    fun `a confirmation dialog is a failure that names the dialog`() {
        // `UserConfirmationRequired` reaches the bus when a session rung raises the platform's own
        // dialog and nobody is there to tap it. `Failed`, not `Unconfirmed`: at that moment the
        // package is not installed, and letting the caller write app data for a package that is not
        // there is worse than stopping. The reason has to name the cause, or the user is told
        // nothing at all — `InstallState.UserConfirmationRequired` carries no message of its own.
        val outcome = archiveInstallOutcome(
            settled = InstallState.UserConfirmationRequired,
            landed = false,
            reason = null,
        )
        assertTrue(outcome is ArchiveInstallOutcome.Failed)
        assertTrue((outcome as ArchiveInstallOutcome.Failed).reason.contains("confirmation"))
    }

    @Test
    fun `a confirmation dialog is decided before the stamp is looked at`() {
        // Ordering, stated as a case: a stale stamp comparison must not turn an unanswered dialog
        // into `Installed`. The dialog is the last word the bus said; nothing after it installed
        // anything.
        val outcome = archiveInstallOutcome(
            settled = InstallState.UserConfirmationRequired,
            landed = true,
            reason = null,
        )
        assertTrue(outcome is ArchiveInstallOutcome.Failed)
    }

    @Test
    fun `the wait ends on success, on error, and on a confirmation dialog`() {
        // The third one is the point. Shizuku's and Dhizuku's session fallback raises the platform
        // dialog; a predicate naming only success and error waits out the entire ten-minute budget
        // for a dialog standing behind a notification that nobody will tap.
        assertTrue(endsArchiveInstallWait(InstallState.Success))
        assertTrue(endsArchiveInstallWait(error()))
        assertTrue(endsArchiveInstallWait(InstallState.UserConfirmationRequired))
    }

    @Test
    fun `the wait does not end on the states an install passes through`() {
        // `Idle` matters on its own: `installBundle` puts it on the bus deliberately, so that the
        // collector's first replayed value is not the previous install's terminal state.
        assertFalse(endsArchiveInstallWait(InstallState.Idle))
        assertFalse(endsArchiveInstallWait(InstallState.Parsing))
        assertFalse(endsArchiveInstallWait(InstallState.Installing(0.5f)))
        assertFalse(
            endsArchiveInstallWait(
                InstallState.ReadyToInstall(
                    meta = AppMetadata(
                        label = "Some Game",
                        packageName = "com.example.game",
                        version = "1.0",
                        versionCode = 1L,
                        iconPath = null,
                    ),
                    isUpdate = false,
                )
            )
        )
    }

    @Test
    fun `the bus's last word beats the one the watcher happened to have processed`() {
        // The regression this function exists for. On the one path that emits two terminal states —
        // root rung, APKs land, `Success`; then minutes of OBB copying fails, `Error` — the
        // collector is normally still holding `Success` when the verdict is read, because nothing
        // yields between the emit and the read. Taking the collector's value reports a clean
        // `Installed` for a game that lost its expansion files, which is #164's own failure mode.
        assertEquals(
            error("game data could not be placed"),
            settledArchiveInstallState(
                lastWord = error("game data could not be placed"),
                watched = InstallState.Success,
            ),
        )
    }

    @Test
    fun `an install still in flight is not a verdict yet`() {
        // A session rung returns at `commit()`, before `InstallReceiver` has answered. The cache's
        // last word is `Installing`, and reading that as the outcome would end the wait on a state
        // that says nothing about how the install went.
        assertNull(
            settledArchiveInstallState(lastWord = InstallState.Installing(1.0f), watched = null),
        )
    }

    @Test
    fun `a terminal state the bus has since moved past is still the verdict`() {
        // The fallback, and why it is not decoration: the collector is the only record of a terminal
        // state that a later non-terminal emission has pushed out of the replay cache. Dropping it
        // would turn a decided install back into a ten-minute wait.
        assertEquals(
            InstallState.Success,
            settledArchiveInstallState(
                lastWord = InstallState.Installing(1.0f),
                watched = InstallState.Success,
            ),
        )
    }

    @Test
    fun `the reset that clears the bus is not mistaken for an outcome`() {
        // `installBundle` puts `Idle` on the bus before installing, so the cache is never a previous
        // install's terminal state. That only works if `Idle` reads as "no verdict yet".
        assertNull(settledArchiveInstallState(lastWord = InstallState.Idle, watched = null))
    }

    @Test
    fun `an obb failure after the app landed survives the read and is not a clean install`() {
        // The same trace end to end, through both functions, as the outcome a caller would act on.
        // This is the assertion that would have gone red on the round it regressed: `Installed`
        // tells Task 14 to proceed as though everything worked.
        val obbFailure = "Some Game installed, but its game data could not be placed: no space left"
        val settled = settledArchiveInstallState(
            lastWord = error(obbFailure),
            watched = InstallState.Success,
        )

        assertEquals(
            ArchiveInstallOutcome.InstalledWithoutGameData(obbFailure),
            archiveInstallOutcome(settled = settled, landed = true, reason = obbFailure),
        )
    }

    @Test
    fun `a stamp that could not be read before the install is not a landing`() {
        // The data-corruption case. `PackageManager` failing on the pre-install read — a binder
        // failure, or a ROM refusing the call — must not look like "nothing was installed before,
        // and something is now". If it did, a *failed* update would score as landed, and
        // `InstalledWithoutGameData` tells the caller to write the new version's app data into the
        // old copy still sitting on disk.
        //
        // Package-visibility filtering is deliberately not in that list: the platform answers a
        // filtered package as not-installed, so it arrives as `Absent` on both sides and is scored
        // by the no-stamp-afterwards rule instead. See `InstallStamp.Absent`.
        assertFalse(
            installLanded(before = InstallStamp.Unknown, after = InstallStamp.At(1_000L)),
        )
    }

    @Test
    fun `a package that was absent and now has a stamp landed`() {
        // The genuine "not installed before" case, which is the one `Unknown` must not be folded
        // into: here the platform actually said so.
        assertTrue(installLanded(before = InstallStamp.Absent, after = InstallStamp.At(1_000L)))
    }

    @Test
    fun `a failed update leaves the stamp where it was and has not landed`() {
        // The case presence gets wrong: the old copy is still installed, so `PackageManager`
        // answers yes about a version that never arrived.
        assertFalse(installLanded(before = InstallStamp.At(1_000L), after = InstallStamp.At(1_000L)))
    }

    @Test
    fun `a stamp that changed is a landing even when the clock moved backwards`() {
        // Compared, not ordered. A device whose clock stepped back mid-install would fail an
        // `after > before` test for an install that plainly happened.
        assertTrue(installLanded(before = InstallStamp.At(9_000L), after = InstallStamp.At(1_000L)))
    }

    @Test
    fun `neither absence nor an unreadable stamp afterwards is a landing`() {
        // Both directions of "no stamp after". The install did not put an app on the device, or
        // nothing can say that it did — and the caller must stop either way.
        assertFalse(installLanded(before = InstallStamp.At(1_000L), after = InstallStamp.Absent))
        assertFalse(installLanded(before = InstallStamp.At(1_000L), after = InstallStamp.Unknown))
        assertFalse(installLanded(before = InstallStamp.Absent, after = InstallStamp.Unknown))
    }

    @Test
    fun `only a corroborated absent-to-present install creates rollback authority`() {
        val outcome = ArchiveInstallOutcome.Installed

        assertEquals(
            ArchiveRollbackReceipt("com.example.app", 2_000L),
            newInstallRollbackReceipt(
                packageName = "com.example.app",
                before = InstallStamp.Absent,
                after = InstallStamp.At(2_000L),
                outcome = outcome,
            ),
        )
        assertNull(
            newInstallRollbackReceipt(
                "com.example.app",
                InstallStamp.Unknown,
                InstallStamp.At(2_000L),
                outcome,
            )
        )
        assertNull(
            newInstallRollbackReceipt(
                "com.example.app",
                InstallStamp.At(1_000L),
                InstallStamp.At(2_000L),
                outcome,
            )
        )
        assertNull(
            newInstallRollbackReceipt(
                "com.example.app",
                InstallStamp.Absent,
                InstallStamp.At(2_000L),
                ArchiveInstallOutcome.Unconfirmed,
            )
        )
    }

    @Test
    fun `rollback uninstalls only the exact package at the exact observed timestamp`() {
        val receipt = ArchiveRollbackReceipt("com.example.app", 2_000L)

        assertEquals(RollbackAction.UNINSTALL, rollbackAction(receipt, InstallStamp.At(2_000L)))
        assertEquals(RollbackAction.ALREADY_ABSENT, rollbackAction(receipt, InstallStamp.Absent))
        assertEquals(RollbackAction.REFUSE, rollbackAction(receipt, InstallStamp.At(2_001L)))
        assertEquals(RollbackAction.REFUSE, rollbackAction(receipt, InstallStamp.Unknown))
    }
}
