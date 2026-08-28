// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.AppArchiveInstaller
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
    // Two uses, both narrow: resolving the bus's `UiText` into the reason an outcome carries, and
    // the `PackageManager` [installStamp] reads. Koin binds the Application, whose `getResources()`
    // follows the applied language (see `AppLocale`), so a string resolved here is in the language
    // the user picked rather than the one the process started in.
    private val context: Context,
    private val installerRepository: InstallerRepository,
    private val eventBus: InstallerEventBus,
    private val obbInstaller: ObbInstaller,
    private val privilegeState: PrivilegeStateProvider,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppArchiveInstaller {

    override suspend fun installBundle(
        bundle: File,
        packageName: String,
        execution: PrivilegeExecutionContext,
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

        installAndAwait(bundle, packageName, mode, execution)
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
     *  - **The verdict is the last word the bus was *given*, not the last one the watcher has
     *    *processed*.** `installPackage` emits `Success` and then, for an OBB-carrying archive,
     *    spends minutes placing game data and can emit `Error` after it (`InstallerRepositoryImpl`'s
     *    "installed, but its game data could not be placed"). Both are terminal and the second one
     *    is the true outcome, so a collector that stopped at the first would report `Installed` for
     *    a game that lost its expansions. Recording terminal states into a `MutableStateFlow` and
     *    reading it afterwards does not fix that either, and this is the precondition worth stating:
     *    `filterNotNull().first()` on a `StateFlow` returns the *current* value without suspending,
     *    `MutableSharedFlow.emit` under `DROP_OLDEST` never suspends, and this caller is already on
     *    `ioDispatcher` so `installPackage`'s own `withContext` resumes undispatched — there is no
     *    yield anywhere between that `Error` reaching the bus and the read, so the watcher is
     *    normally still holding `Success` and the error is discarded. The read therefore goes to
     *    [InstallerEventBus.latest], the `replay = 1` cache, which the emitting thread fills
     *    synchronously before `installPackage` returns. [settledArchiveInstallState] states that
     *    rule as a pure function of the two values, so it is decided by data rather than by which
     *    thread won, and it is tested.
     *  - **The watcher is still what makes the fallback safe.** On a session rung the outcome
     *    arrives *after* `installPackage` returns — `InstallReceiver` answers a commit that has
     *    already been made — so the cache's last word is only `Installing`. Subscribing after that
     *    read would race the broadcast; subscribing before the install, as below, cannot.
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
        execution: PrivilegeExecutionContext,
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
                        execution = execution,
                    )
                    // `latest` is the last word the bus was given; `terminal.value` is only the
                    // last word this watcher has caught up with. They differ on exactly the path
                    // this outcome type exists for — see the second bullet above.
                    settledArchiveInstallState(
                        lastWord = eventBus.latest,
                        watched = terminal.value,
                    ) ?: terminal.filterNotNull().first()
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
        val outcome = archiveInstallOutcome(
            settled = settled,
            landed = installLanded(before = stampBefore, after = stampAfter),
            reason = (settled as? InstallState.Error)?.message?.asString(context),
        )
        Logger.i(TAG, "installAndAwait finished for $packageName: mode=$mode, settled=$settled, stampBefore=$stampBefore, stampAfter=$stampAfter, outcome=$outcome")
        return outcome
    }

    /**
     * What the platform can say about [packageName]'s `lastUpdateTime` right now.
     *
     * A *change* in that value is the one locally observable proof that this install landed. The
     * platform stamps it on every completed install, including a reinstall of the same version, so
     * it answers "did this operation put an app on the device?" where presence answers only "is
     * there an app of this name?".
     *
     * Asked of `PackageManager` directly, and **not** through `AppRepository.getAppDetails`: that
     * builds a whole `AppInfo` — a label loaded out of the target app's resources, an
     * `Environment` lookup, `getInstallSourceInfo`, a UAD-map hit — twice per restore, to read one
     * `Long`. `InstallerRepositoryImpl.installStamp` already reads it this way for the same purpose.
     *
     * The three-way return is the point rather than a detail. `getAppDetails` swallows every
     * exception and returns null, which makes "there is no such package" and "the question could
     * not be answered" the same value — and answering the second one as the first is what lets a
     * failed update be scored as a landing. See [installLanded].
     *
     * Not `suspend`, and no `CancellationException` catch: `getPackageInfo` is one synchronous
     * binder call with no suspension point, so a cancellation cannot be delivered inside this `try`
     * and a catch for it would be the dead idiom rather than the guard it looks like.
     */
    private fun installStamp(packageName: String): InstallStamp = try {
        InstallStamp.At(context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime)
    } catch (_: PackageManager.NameNotFoundException) {
        InstallStamp.Absent
    } catch (e: Exception) {
        // A binder failure, or a ROM refusing the call outright. **Not** package-visibility
        // filtering: the platform's contract there is to behave as though the package does not
        // exist, so a filtered package throws `NameNotFoundException` and lands in `Absent` above —
        // `InstallerLabelResolverImpl.labelFor` catches it the same way. That is safe here, because a
        // filtered package reads `Absent` on *both* sides and [installLanded] scores that false.
        // Logged because it silently costs the caller the difference between `Failed` and a data
        // restore.
        Logger.e(TAG, "could not read the install stamp for $packageName", e)
        InstallStamp.Unknown
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
 * The terminal state a restore's install wait has actually reached, or null when it has not.
 *
 * Two sources, in this order, and the order is the whole content of this function:
 *
 *  - [lastWord] is `InstallerEventBus.latest`, the `replay = 1` cache. The emitting thread fills it
 *    synchronously, so once `installPackage` has returned it holds the last thing that call said —
 *    including an `Error` emitted **after** a `Success` on the same install, which is what an
 *    OBB-carrying archive whose expansions could not be placed produces. It wins.
 *  - [watched] is the last terminal state a subscribed collector has processed. It is a *lagging*
 *    view: nothing forces that collector to have run before this is read. It is used only when the
 *    bus's last word is not terminal at all — a session rung has committed and `InstallReceiver`
 *    has not answered yet, so the cache holds `Installing` while the collector may already hold the
 *    outcome from a moment ago.
 *
 * Null means neither source has a verdict and the caller must keep waiting. The `Idle` that
 * `installBundle` puts on the bus before installing lands here, which is what stops the reset from
 * being read as an outcome.
 *
 * Pure, and separated from the coroutine that feeds it, precisely because the defect it fixes was a
 * thread race: a test of the racing code would be flaky, whereas a test of this is red or green.
 */
internal fun settledArchiveInstallState(
    lastWord: InstallState?,
    watched: InstallState?,
): InstallState? = lastWord?.takeIf { endsArchiveInstallWait(it) } ?: watched

/**
 * What `PackageManager` could say about a package's `lastUpdateTime` at one moment.
 *
 * Three cases, not a nullable `Long`, because "no stamp" has two meanings that must not be folded
 * together: [Absent] is the platform stating the package is not installed, and [Unknown] is the
 * platform failing to answer. [installLanded] scores those differently, and treating the second as
 * the first is how a restore writes one app version's data into another.
 */
internal sealed interface InstallStamp {

    /** The platform's stamp, in milliseconds. */
    data class At(val millis: Long) : InstallStamp

    /**
     * The platform said there is no such package.
     *
     * Which includes a package hidden from Thor by **package-visibility filtering** — the platform
     * answers those as not-installed rather than as an error. Harmless here: a filtered package
     * reads [Absent] on both sides of the install, and [installLanded] scores that no landing.
     */
    data object Absent : InstallStamp

    /** The platform could not be asked — a binder failure, or a ROM refusing the call. */
    data object Unknown : InstallStamp
}

/**
 * Whether *this* install put an app on the device, from the stamp on either side of it.
 *
 * The rules, and why each is what it is:
 *
 *  - **[InstallStamp.Unknown] on either side is never a landing.** A read that failed is not
 *    evidence, and this side is not symmetric in consequence: a false `landed` on an error turns
 *    `Failed` into `InstalledWithoutGameData`, whose contract tells the restore to go on and write
 *    app data — into whatever copy was already there. `getAppDetails` returning null for a binder
 *    hiccup used to reach exactly that, because a swallowed exception and an uninstalled package
 *    were the same value.
 *  - **[InstallStamp.Absent] before and a stamp after is a landing.** Nothing was there, something
 *    is now.
 *  - **Two stamps are compared, not ordered.** `!=`, so a clock that moved backwards between the
 *    two reads still reads as a change rather than as no install.
 *  - **The same stamp on both sides is not a landing**, which is the case presence gets wrong: a
 *    failed *update* leaves the previous copy installed for `PackageManager` to answer yes about.
 *  - **No stamp afterwards is never a landing**, whichever kind of no-stamp it is.
 */
internal fun installLanded(before: InstallStamp, after: InstallStamp): Boolean = when (after) {
    is InstallStamp.At -> when (before) {
        is InstallStamp.At -> after.millis != before.millis
        InstallStamp.Absent -> true
        InstallStamp.Unknown -> false
    }

    InstallStamp.Absent, InstallStamp.Unknown -> false
}

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
 *   Computed by [installLanded], which also refuses to call a stamp it could not read a landing.
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
