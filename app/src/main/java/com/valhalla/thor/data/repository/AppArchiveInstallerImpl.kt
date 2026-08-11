// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import androidx.core.net.toUri
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [AppArchiveInstaller::class])
class AppArchiveInstallerImpl(
    private val installerRepository: InstallerRepository,
    private val eventBus: InstallerEventBus,
    private val appRepository: AppRepository,
    private val obbInstaller: ObbInstaller,
    private val privilegeState: PrivilegeStateProvider,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppArchiveInstaller {

    override suspend fun installBundle(
        bundle: File,
        packageName: String,
    ): ArchiveInstallOutcome = withContext(ioDispatcher) {
        if (!bundle.isFile || bundle.length() == 0L) {
            return@withContext ArchiveInstallOutcome.Failed("the archive's app bundle is missing")
        }

        val mode = installMode()
            ?: return@withContext ArchiveInstallOutcome.Failed(
                "Thor has no privileged access, so it cannot install this app"
            )

        // The bus is a replaying SharedFlow shared with PortableInstallerActivity. Without a reset, a
        // stale `Success` from an earlier install in this process is read as this one's outcome.
        eventBus.reset()

        val failure = runCatching {
            installerRepository.installPackage(
                staged = StagedPackage(file = bundle, displayName = bundle.name),
                // Only InstallMode.EXTERNAL reads this, and EXTERNAL is not reachable here — restore
                // never hands the job to another installer app.
                uri = bundle.toUri(),
                mode = mode,
                canDowngrade = true,
            )
        }.exceptionOrNull()

        if (failure != null) {
            // `runCatching` catches `Throwable`, cancellation included, so this rethrow is what keeps
            // a cancelled restore from being reported as a failed install — and keeps this coroutine
            // from looking as though it completed normally after its job was cancelled.
            if (failure is CancellationException) throw failure
            Logger.e(TAG, "install of $packageName threw", failure)
            return@withContext ArchiveInstallOutcome.Failed(failure.message ?: "the install failed")
        }

        awaitOutcome(packageName)
    }

    /**
     * The install rung to use, or null when there is none.
     *
     * Read from the live privilege state rather than pinned to [InstallMode.ROOT], because the
     * feature is not root-gated: `DataArchiveCapabilityCache` admits any privilege that answers the
     * probe, so a Shizuku-only device reaches here and a hardcoded `ROOT` would send it down a rung
     * it does not have. [InstallMode.NORMAL] is deliberately not a fallback — it raises the
     * platform's own confirmation dialog, and there is nobody in front of the screen during a
     * restore. [InstallMode.EXTERNAL] is not one either: it hands the file to another installer app
     * and returns before anything is installed, so the data write that follows would land in
     * whatever copy happened to be on the device.
     *
     * `first { it.isReady }` rather than `state.value`: the snapshot on cold start is the default
     * `active = NONE`, which would refuse the install on a rooted device purely for being early.
     * Same read, and same reason, as `DataArchiveCapabilityCache.isSupported`.
     */
    private suspend fun installMode(): InstallMode? =
        when (privilegeState.state.first { it.isReady }.active) {
            PrivilegeMode.ROOT -> InstallMode.ROOT
            PrivilegeMode.SHIZUKU -> InstallMode.SHIZUKU
            PrivilegeMode.DHIZUKU -> InstallMode.DHIZUKU
            PrivilegeMode.NONE -> null
        }

    /**
     * Wait on the bus, then confirm against `PackageManager`.
     *
     * Both, not either. `session.commit()` is fire-and-forget, so the bus is the only thing that knows
     * the install finished; and the bus is a process-wide flow, so a `Success` on it is confirmed
     * against the package actually being there before any data is written into it.
     *
     * The bus replays one value, and `reset()` above put [InstallState.Idle] there — so the first
     * value this collector sees is `Idle`, which the predicate skips, and no drop of the replay cache
     * is needed. The replay is in fact load-bearing in the other direction: `installPackage` can
     * settle before this collector subscribes (the root rung emits `Success` inline), and without a
     * replay that terminal state would be missed and every install would time out as `Unconfirmed`.
     */
    private suspend fun awaitOutcome(packageName: String): ArchiveInstallOutcome {
        val settled = withTimeoutOrNull(INSTALL_WAIT_MS) {
            eventBus.events.first { it is InstallState.Success || it is InstallState.Error }
        }
        // Only asked once the wait has settled, and never when it produced an error: on the error
        // path the answer is about the *previous* copy of the app, not this install.
        val installed = settled is InstallState.Success && appRepository.getAppDetails(packageName) != null
        return archiveInstallOutcome(settled, installed)
    }

    override suspend fun placeBundleObb(
        bundle: File,
        packageName: String,
        onFile: (String, Int, Int) -> Unit,
    ): ObbPlacement = obbInstaller.placeStreaming(bundle, packageName, onFile)

    companion object {
        private const val TAG = "AppArchiveInstaller"

        /**
         * Generous, because this covers a multi-hundred-megabyte split install on a slow device. It
         * ends in [ArchiveInstallOutcome.Unconfirmed] rather than in silence — the same choice
         * `InstallerRepositoryImpl.awaitInstalled` makes.
         */
        private const val INSTALL_WAIT_MS = 10 * 60 * 1000L
    }
}

/**
 * The verdict [AppArchiveInstallerImpl.awaitOutcome] reaches, as a pure function of what it saw.
 *
 * Top-level and `internal` for testability, the same trade [streamObbEntries] makes in this package:
 * `AppArchiveInstallerImpl` takes an `ObbInstaller`, which needs a `Context`, and there is no mocking
 * library on the unit-test classpath — so the class cannot be constructed off-device, and this
 * decision would otherwise ship untested.
 *
 * The order of the branches is the contract:
 *
 *  - **A wait that ran out is [ArchiveInstallOutcome.Unconfirmed], never [ArchiveInstallOutcome.Failed].**
 *    `session.commit()` is fire-and-forget, so an install that actually landed can still reach the
 *    timeout; calling that a failure tells a user their restore did not install when it did.
 *  - **An error is tested before presence.** A failed *update* leaves the old copy installed, so
 *    `PackageManager` answers yes to a question about a version that never landed. Reading presence
 *    first would call that `Installed` and let the caller write the new version's data into the old
 *    app.
 *  - **A `Success` nobody can corroborate is [ArchiveInstallOutcome.Unconfirmed] too.** The bus is
 *    process-wide, so a `Success` on it is not proof that *this* package is there.
 *
 * @param settled the terminal state the bus produced, or null when the wait ran out.
 * @param installed whether `PackageManager` can see the package. Meaningful only when [settled] is a
 *   success; the caller does not ask otherwise.
 */
internal fun archiveInstallOutcome(
    settled: InstallState?,
    installed: Boolean,
): ArchiveInstallOutcome = when {
    settled == null -> ArchiveInstallOutcome.Unconfirmed
    settled is InstallState.Error ->
        // The bus already carries the platform's own reason; a second sentence about restore would
        // bury the cause under one of its consequences.
        ArchiveInstallOutcome.Failed("the app could not be installed")

    installed -> ArchiveInstallOutcome.Installed
    else -> ArchiveInstallOutcome.Unconfirmed
}
