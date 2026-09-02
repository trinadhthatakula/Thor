// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import java.io.File

data class ArchiveRollbackReceipt(
    val packageName: String,
    val installedAt: Long,
)

data class ArchiveInstallResult(
    val outcome: ArchiveInstallOutcome,
    val rollbackReceipt: ArchiveRollbackReceipt? = null,
)

enum class ArchiveRollbackOutcome {
    CLEAN,
    REFUSED,
    FAILED,
}

/** How an install-from-archive ended. */
sealed interface ArchiveInstallOutcome {

    data object Installed : ArchiveInstallOutcome

    /**
     * The app is not on the device, and [reason] says why.
     *
     * [reason] is the platform's own words — the message the install path put on
     * `InstallerEventBus`, resolved to a string. Nobody is subscribed to that bus during a
     * background restore, so this field is the only place it survives, and it is routinely the only
     * actionable thing anyone will see: "switch to root or Shizuku and try again" is a sentence a
     * user can act on, and the flat "the app could not be installed" it used to be replaced with is
     * not.
     */
    data class Failed(val reason: String) : ArchiveInstallOutcome

    /**
     * The app **is** installed and current, but something after the install itself failed — in
     * practice its game data could not be placed.
     *
     * Its own outcome because the caller's next decision differs from [Failed]'s. The package is
     * there, so restoring its data is both possible and the useful thing to do; reporting this as
     * [Failed] leaves the user with an installed, empty app and no path forward but to start again.
     * The caller should proceed **and** surface [reason], because a game whose expansions are
     * missing starts and then crashes.
     *
     * Told apart from [Failed] without reading any message text: `lastUpdateTime` moved while the
     * install ran, which is the platform's own record that *this* install landed. Presence cannot
     * answer that — restoring over an existing install is the normal case, and a failed update
     * leaves the old copy in place for `PackageManager` to say yes about.
     */
    data class InstalledWithoutGameData(val reason: String) : ArchiveInstallOutcome

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
    suspend fun installBundle(
        bundle: File,
        packageName: String,
        installSet: List<String>,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): ArchiveInstallResult

    /**
     * Remove only the package proven by [receipt] to have been absent before this restore installed it.
     * The caller already holds the package-operation lease; implementations must not acquire another.
     */
    suspend fun rollbackNewInstall(
        receipt: ArchiveRollbackReceipt,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): ArchiveRollbackOutcome

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
