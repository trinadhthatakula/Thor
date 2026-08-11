// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict `AppArchiveInstallerImpl.installAndAwait` reaches, hoisted into
 * [archiveInstallOutcome] so it can be checked without a `Context`.
 *
 * Same trade the rest of this task makes: `AppArchiveInstallerImpl` takes `ObbInstaller`, which needs
 * a `Context`, and there is no mocking library on this classpath — so the decision worth locking down
 * is the one that is pure, and it is locked down here rather than left to a device.
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
}
