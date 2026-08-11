// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [AppArchiveInstaller::class])
class AppArchiveInstallerImpl(
    // Only ever used to resolve the bus's `UiText` into the reason an outcome carries. Koin binds
    // the Application, whose `getResources()` follows the applied language (see `AppLocale`), so a
    // string resolved here is in the language the user picked rather than the one the process
    // started in.
    private val context: Context,
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

        // The bus is a replaying SharedFlow shared with PortableInstallerActivity, and it replays its
        // last value to every new subscriber — so without this reset the collector below would be
        // handed an *earlier* install's terminal state the instant it subscribes and read it as this
        // one's. `Idle` is what it sees instead, and the predicate skips it.
        eventBus.reset()

        installAndAwait(bundle, packageName, mode)
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
     * Run the install with the bus already being watched, and turn what the bus said into a verdict.
     *
     * Three properties, none of which the obvious "install, then wait" shape has:
     *
     *  - **The collector is subscribed before `installPackage` is called.** The install path emits
     *    its terminal state from inside that call — the root and Shizuku shell rungs emit `Success`
     *    inline — so a collector that subscribes afterwards is relying on the replay cache to have
     *    kept exactly the value it wants. It also means the wait below genuinely starts when the
     *    install does.
     *  - **The last terminal state wins, not the first.** This is the precondition the plain
     *    `first { … }` breaks here: `installPackage` emits `Success` and *then*, for an
     *    OBB-carrying archive, spends minutes placing game data and can emit `Error` after it
     *    (`InstallerRepositoryImpl`'s "installed, but its game data could not be placed"). A
     *    subscribe-early collector that stopped at the first terminal state would return `Installed`
     *    and throw that error away — worse than the late subscribe it replaced, which at least read
     *    the last value out of the replay cache. So the collector records terminal states and the
     *    verdict is taken once `installPackage` has returned, i.e. once it has said everything it
     *    is going to say.
     *  - **[INSTALL_WAIT_MS] bounds the whole operation.** The install call is inside the budget,
     *    not just the wait after it, so a rung that never returns ends in `Unconfirmed` instead of
     *    hanging the restore worker with no outcome at all.
     *
     * What this does *not* fix, because the constant is in `InstallerRepositoryImpl` and shared with
     * the foreground installer: for an OBB-carrying archive installed through a **session** rung
     * (Shizuku's reflection fallback, or the normal-installer fallback below it), `installPackage`
     * runs its own 90 s `awaitInstalled` and emits "Thor could not confirm … finished installing" on
     * timeout. That error settles this wait at 90 s even though the budget here is ten minutes. The
     * shell rungs — root, and Shizuku's first rung — are synchronous and never reach it. The reason
     * now travels with the outcome, so the user is told the install could not be confirmed rather
     * than that it failed.
     */
    private suspend fun installAndAwait(
        bundle: File,
        packageName: String,
        mode: InstallMode,
    ): ArchiveInstallOutcome {
        // Read before the install, because for an update the answer changes and nothing afterwards
        // can reconstruct it. `InstallerRepositoryImpl.awaitInstalled` reads the same stamp for the
        // same reason: presence cannot say whether *this* install landed, since restoring over an
        // existing copy is the normal case and a failed update leaves the old one there.
        val stampBefore = installStamp(packageName)

        val settled = try {
            withTimeoutOrNull(INSTALL_WAIT_MS) {
                val terminal = MutableStateFlow<InstallState?>(null)
                val subscribed = CompletableDeferred<Unit>()
                val watcher = launch {
                    eventBus.events
                        .onSubscription { subscribed.complete(Unit) }
                        .collect { if (endsArchiveInstallWait(it)) terminal.value = it }
                }
                try {
                    // `onSubscription`'s documented guarantee is that its action runs *after* the
                    // subscription is registered and before any value reaches this collector. That
                    // is what makes this await a real happens-before; awaiting the `launch` itself
                    // would only prove the coroutine was scheduled.
                    subscribed.await()
                    installerRepository.installPackage(
                        staged = StagedPackage(file = bundle, displayName = bundle.name),
                        // Only InstallMode.EXTERNAL reads this, and EXTERNAL is not reachable here —
                        // restore never hands the job to another installer app.
                        uri = bundle.toUri(),
                        mode = mode,
                        canDowngrade = true,
                    )
                    terminal.filterNotNull().first()
                } finally {
                    // `events` is a SharedFlow and never completes, so the collector never ends on
                    // its own and the enclosing scope would wait for it forever.
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            // Physically before the `Throwable` catch or it is dead code. A cancelled restore must
            // not be reported as a failed install, and this coroutine must not look as though it
            // completed normally after its job was cancelled. `installPackage` rethrows cancellation
            // from every one of its own catch sites, so this is a live path.
            throw e
        } catch (e: Throwable) {
            // Throwable rather than Exception, matching `installPackage`'s own outer catch: an
            // `Error` escaping here would kill the restore worker instead of failing one install.
            Logger.e(TAG, "install of $packageName threw", e)
            return ArchiveInstallOutcome.Failed(e.message ?: "the install failed")
        }

        val stampAfter = installStamp(packageName)
        return archiveInstallOutcome(
            settled = settled,
            landed = stampAfter != null && stampAfter != stampBefore,
            reason = (settled as? InstallState.Error)?.message?.asString(context),
        )
    }

    /**
     * The platform's `lastUpdateTime` for [packageName], or null when it cannot see the package.
     *
     * A *change* in this value is the one locally observable proof that this install landed. The
     * platform stamps it on every completed install, including a reinstall of the same version, so
     * it answers "did this operation put an app on the device?" where presence answers only "is
     * there an app of this name?".
     */
    private suspend fun installStamp(packageName: String): Long? =
        appRepository.getAppDetails(packageName)?.lastUpdateTime

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

/** Stated when the bus reported an error but carried no words with it. */
private const val UNSTATED_FAILURE_REASON = "the app could not be installed"

/** Stated for [InstallState.UserConfirmationRequired]: it names the cause, not the symptom. */
private const val CONFIRMATION_REQUIRED_REASON =
    "this install asks for the system's own confirmation dialog, and nothing can answer it during " +
        "a background restore"

/**
 * Whether [state] ends the wait a restore does on the install bus.
 *
 * `UserConfirmationRequired` is in here, and that is most of the reason this predicate exists.
 * Shizuku's and Dhizuku's third fallback rung creates a session under Thor's own uid, which raises
 * the platform's confirmation dialog; nobody taps it during a background restore, so the bus's next
 * and only word is a state that is neither a success nor an error. A predicate naming only those two
 * waits out the entire ten-minute budget for a dialog standing behind a notification.
 */
internal fun endsArchiveInstallWait(state: InstallState): Boolean =
    state is InstallState.Success ||
        state is InstallState.Error ||
        state is InstallState.UserConfirmationRequired

/**
 * The verdict `AppArchiveInstallerImpl.installAndAwait` reaches, as a pure function of what it saw.
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
 *  - **A confirmation dialog is a failure, and it is decided before [landed] is looked at.** At that
 *    moment the package is not installed, and letting the caller write app data for a package that
 *    is not there is worse than stopping.
 *  - **An error while the app did land is [ArchiveInstallOutcome.InstalledWithoutGameData].** That
 *    is the "installed, but its game data could not be placed" path: reporting it as [failed][ArchiveInstallOutcome.Failed]
 *    is a factually false statement about a package that is on the device, and it costs the user the
 *    data restore that would still have worked.
 *  - **An error while it did not is [ArchiveInstallOutcome.Failed], carrying [reason].** Which of
 *    the two it is, is decided by [landed] and never by the text of [reason] — text matching is
 *    brittle and breaks the first time these strings are localised.
 *  - **A `Success` nobody can corroborate is [ArchiveInstallOutcome.Unconfirmed] too.** The bus is
 *    process-wide, so a `Success` on it is not proof that *this* install landed.
 *
 * @param settled the terminal state the bus produced, or null when the wait ran out.
 * @param landed whether the package's `lastUpdateTime` moved while this install ran — i.e. whether
 *   *this* operation put an app on the device. Not "is something of that name installed": a failed
 *   update leaves the old copy behind, and restoring over an existing install is the normal case.
 * @param reason the settled error's own message, resolved, or null when there is none to carry.
 */
internal fun archiveInstallOutcome(
    settled: InstallState?,
    landed: Boolean,
    reason: String?,
): ArchiveInstallOutcome {
    val stated = if (reason.isNullOrBlank()) UNSTATED_FAILURE_REASON else reason
    return when {
        settled == null -> ArchiveInstallOutcome.Unconfirmed
        settled is InstallState.UserConfirmationRequired ->
            ArchiveInstallOutcome.Failed(CONFIRMATION_REQUIRED_REASON)

        settled is InstallState.Error && landed ->
            ArchiveInstallOutcome.InstalledWithoutGameData(stated)

        settled is InstallState.Error -> ArchiveInstallOutcome.Failed(stated)
        landed -> ArchiveInstallOutcome.Installed
        else -> ArchiveInstallOutcome.Unconfirmed
    }
}
