// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ObbPlacement
import java.io.File

/** How an install-from-archive ended. */
sealed interface ArchiveInstallOutcome {

    data object Installed : ArchiveInstallOutcome

    data class Failed(val reason: String) : ArchiveInstallOutcome

    /**
     * The install neither succeeded nor reported an error inside the timeout.
     *
     * Its own outcome, not folded into [Failed]: restoring data into a package whose install Thor
     * could not confirm is how you write someone's data into a half-installed app. The caller stops,
     * and says so.
     */
    data object Unconfirmed : ArchiveInstallOutcome
}

/**
 * Installs an app from the `.xapk` inside an archive, and places that bundle's expansions.
 *
 * A narrow port rather than a call into `InstallerRepository` for two reasons. `installPackage` takes
 * an `android.net.Uri`, which would take the restore use case off the JVM test classpath (see
 * [ArchiveSource]). And the install result does not come back from `installPackage` at all — it
 * arrives later on `InstallerEventBus`, because `session.commit()` returns before the platform has
 * installed anything. Both of those are implementation facts, and this is where they stay.
 */
interface AppArchiveInstaller {

    /**
     * Install [bundle], then wait for the outcome.
     *
     * Waits on the install result rather than polling `isInstalled()`: §8.2. Returns only once the
     * install has landed, failed, or the wait has run out.
     */
    suspend fun installBundle(bundle: File, packageName: String): ArchiveInstallOutcome

    /**
     * Place [bundle]'s expansions into `Android/obb/<pkg>/` for an app that is **already installed**
     * (§8.4), one file at a time.
     *
     * Not called after [installBundle] — that path places OBB itself.
     *
     * @param onFile leaf name, 1-based position, total.
     */
    suspend fun placeBundleObb(
        bundle: File,
        packageName: String,
        onFile: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): ObbPlacement
}
