// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three-way verdict `AppArchiveInstallerImpl.awaitOutcome` reaches, hoisted into
 * [archiveInstallOutcome] so it can be checked without a `Context`.
 *
 * Same trade the rest of this task makes: `AppArchiveInstallerImpl` takes `ObbInstaller`, which needs
 * a `Context`, and there is no mocking library on this classpath — so the decision worth locking down
 * is the one that is pure, and it is locked down here rather than left to a device.
 *
 * What is actually at stake is whether restore writes someone's data into the wrong app. Every case
 * below is a distinct way of getting that wrong.
 */
class ArchiveInstallOutcomeTest {

    private fun error() = InstallState.Error(UiText.DynamicString("INSTALL_FAILED_INVALID_APK"))

    @Test
    fun `a wait that runs out is unconfirmed, never failed`() {
        // The rule this whole outcome type exists for. `session.commit()` is fire-and-forget, so a
        // restore that actually worked can time out here; calling that `Failed` tells the user their
        // app did not install when it did, and is the bug `Unconfirmed` was added to prevent.
        assertEquals(
            ArchiveInstallOutcome.Unconfirmed,
            archiveInstallOutcome(settled = null, installed = false),
        )
    }

    @Test
    fun `a success the package manager cannot see is unconfirmed, not installed`() {
        // The bus is process-wide. A `Success` on it that PackageManager cannot corroborate is not
        // this install's success, and writing data into that package name is how you fill a
        // half-installed app.
        assertEquals(
            ArchiveInstallOutcome.Unconfirmed,
            archiveInstallOutcome(settled = InstallState.Success, installed = false),
        )
    }

    @Test
    fun `a success the package manager can see is installed`() {
        assertEquals(
            ArchiveInstallOutcome.Installed,
            archiveInstallOutcome(settled = InstallState.Success, installed = true),
        )
    }

    @Test
    fun `an error wins over the package being present`() {
        // `installed = true` deliberately: a *failed update* leaves the OLD copy installed, so
        // PackageManager answers yes. Testing presence before the bus state would call that
        // `Installed` and restore the new version's data into the old app.
        assertEquals(
            ArchiveInstallOutcome.Failed("the app could not be installed"),
            archiveInstallOutcome(settled = error(), installed = true),
        )
    }
}
