// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.shizuku.EnableRungOrder
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.data.source.local.shizuku.SystemAppRemovalOutcome
import com.valhalla.thor.data.source.local.shizuku.displayLine
import com.valhalla.thor.data.source.local.shizuku.isRootOnlySystemAppRemoval
import com.valhalla.thor.data.source.local.SessionApk
import com.valhalla.thor.data.source.local.asComponentState
import com.valhalla.thor.data.source.local.ComponentCommandKind
import com.valhalla.thor.data.source.local.componentCommandFailure
import com.valhalla.thor.data.source.local.escapedComponentSpecOrNull
import com.valhalla.thor.data.source.local.installViaSessionCommand
import com.valhalla.thor.data.source.local.installedAppsAppOpGrantCommands
import com.valhalla.thor.data.source.local.installedAppsAppOpRevokeCommands
import com.valhalla.thor.data.source.local.pmPathCommand
import com.valhalla.thor.data.source.local.setComponentStateCommand
import com.valhalla.thor.data.source.local.startActivityCommand
import com.valhalla.thor.data.source.local.stopServiceCommand
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.GET_INSTALLED_APPS_PERMISSION
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.uninstallFreezeFallbackAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import rikka.shizuku.Shizuku
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.first
import java.io.File

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

@Single
class ShizukuSystemGateway internal constructor(
    // Present for one reason: the system-app freeze's refusal message is read by the user, so it
    // has to come out of resources. RootSystemGateway takes its Context the same way.
    private val context: Context,
    private val reflector: ShizukuReflector,
    private val preferenceRepository: PreferenceRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    private val reinstallPostconditionVerifier: ReinstallPostconditionVerifier =
        ReinstallPostconditionVerifier(AndroidReinstallStateReader(context)),
) : SystemGateway {

    override suspend fun isRootAvailable(
        execution: PrivilegeExecutionContext,
    ) = false

    // Shizuku.checkSelfPermission()/pingBinder() are blocking binder IPC; confine them to IO
    // at the gateway boundary so this probe is main-safe regardless of the caller's dispatcher.
    override suspend fun isShizukuAvailable(): Boolean = withContext(ioDispatcher) {
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun isDhizukuAvailable(): Boolean = false

    override suspend fun executeShellCommand(
        command: String,
        execution: PrivilegeExecutionContext,
    ): Result<Pair<Int, String?>> {
        // Runs through Shizuku's privileged process (shell uid), same path as in-app actions.
        return try {
            Result.success(ShizukuHelper.execute(command))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    // --- Per-component control -------------------------------------------------------------
    //
    // The one group of verbs on this interface with no shell rung and no reflection rung at the
    // shell uid. `PackageManagerService.setEnabledSetting` carves out `Process.SHELL_UID` only for
    // calls with a null class name — a per-component call throws
    // `SecurityException("Shell cannot change component state for …")` — and
    // `ActivityManager.canAccessUnexportedComponents` waives the launch checks for `ROOT_UID` and
    // `SYSTEM_UID` alone. Going through `reflector` instead of through `pm`/`am` changes nothing:
    // the binder call arrives at the same check carrying the same calling uid.
    //
    // A Shizuku that was started **as root** (`su -c sh /storage/…/starter.sh`, or Magisk's Shizuku
    // module) runs its service at uid 0 and can do all three. That is the only case that succeeds
    // here, and asking `Shizuku.getUid()` is the only way to tell the two apart — availability is
    // permission-plus-`pingBinder`, which is equally true at uid 2000.

    override suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = runComponentCommand(packageName, className) { spec ->
        setComponentStateCommand(spec, userId, state.asComponentState())
    }

    override suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = runComponentCommand(packageName, className) { spec ->
        startActivityCommand(spec, userId)
    }

    override suspend fun stopService(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = runComponentCommand(
        packageName,
        className,
        ComponentCommandKind.STOP_SERVICE,
    ) { spec ->
        stopServiceCommand(spec, userId)
    }

    /**
     * Refuse unless this Shizuku is uid 0, then run [build] and judge it by its output.
     *
     * The refusal is a resource string, not an English literal, because it is read by the user and
     * every caller funnels a failure's `message` into `R.string.error_format` — an untranslated
     * sentence inside a translated one is the shape `clearAllCaches` above already documents.
     *
     * An unreadable uid refuses. `Shizuku.getUid()` throws when the binder has gone since the last
     * availability check, and the alternative — assuming root — paints working controls that throw a
     * `SecurityException` on every press.
     *
     * [ShizukuHelper.executeCombined] rather than `execute`, and this is the only caller of it in
     * the codebase. `execute` returns stdout *or* stderr, preferring stdout whenever it has
     * anything — and every command here writes its *outcome* to stderr while writing a content-free
     * echo ("Starting: Intent { … }", "Stopping service: Intent { … }") to stdout. Through `execute`
     * the verdict never sees the sentence it exists to read, which made every "Stop now" report a
     * failure and every refused force-launch report the echo instead of the denial.
     */
    private suspend fun runComponentCommand(
        packageName: String,
        className: String,
        kind: ComponentCommandKind = ComponentCommandKind.STANDARD,
        build: (escapedSpec: String) -> String,
    ): Result<Unit> = withContext(ioDispatcher) {
        val isRoot = runCatching { ShizukuHelper.isRoot }.getOrDefault(false)
        if (!isRoot) {
            return@withContext Result.failure(
                Exception(context.getString(R.string.component_control_requires_root_shizuku))
            )
        }
        val spec = escapedComponentSpecOrNull(packageName, className)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Invalid component: $packageName/$className")
            )
        val (code, output) = runCatching { ShizukuHelper.executeCombined(build(spec)) }
            .getOrElse { return@withContext Result.failure(it) }
        val failure = componentCommandFailure(code, output, kind)
        if (failure == null) {
            Result.success(Unit)
        } else {
            Logger.e("ShizukuSystemGateway", "Component command failed: $failure")
            Result.failure(Exception(failure))
        }
    }

    override suspend fun forceStopApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return runAction { reflector.forceStop(packageName) }
    }

    override suspend fun clearAllCaches(
        targetFreeBytes: Long?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        // No sweep fallback, unlike Root: shell uid 2000 cannot delete another package's cache
        // directory, so without a target there is nothing left to try.
        //
        // What is missing when this is null is the *cache total*, not free space — free space needs
        // no permission. A `pm trim-caches` target is free-space-plus-what-to-reclaim, so the size
        // of the cache is half the sum, and only the usage-access op can supply it. Guessing the
        // other half is not an option: too low and PMS returns on its first line having done
        // nothing, too high and it walks past the cache rungs into pruning shared libraries and
        // uninstalling instant apps. Naming the op is what makes this message actionable — and it
        // is the one message on this path the user can *act* on, which is why it comes out of
        // resources while the diagnostic below does not. `MainViewModel.quickAction` puts
        // `e.message` straight into R.string.error_format, so an English literal here would be an
        // English sentence inside a translated one.
        if (targetFreeBytes == null) {
            return Result.failure(
                Exception(context.getString(R.string.clear_all_caches_requires_usage_access))
            )
        }
        // `trimCaches` reports the exit code, and `pm trim-caches` exits 0 even when it frees
        // nothing, so this true is "the command ran" and never "the cache is gone". Nothing here can
        // do better; what the user is told comes from SystemRepositoryImpl measuring the cache on
        // either side of this call.
        return if (reflector.trimCaches(targetFreeBytes)) Result.success(Unit)
        else Result.failure(Exception("Shizuku: `pm trim-caches` failed."))
    }

    override suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return runAction { reflector.clearData(packageName) }
    }

    override suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        // FLAG_SYSTEM alone, never OR'd with FLAG_UPDATED_SYSTEM_APP — ShizukuReflector.isSystemApp
        // is written that way, matching AppInfoMapper, AppFreezeStateReader.candidateOf and
        // RootSystemGateway.setAppDisabled. The destructive-fallback gate below is keyed on this
        // same answer, so a second definition of "system" here would gate a different set of apps
        // than the one the freeze itself acts on.
        val isSystem = reflector.isSystemApp(packageName)
        return if (isSystem) {
            if (isDisabled) freezeSystemApp(packageName) else unfreezeSystemApp(packageName)
        } else {
            // Unchanged, and deliberately so: a user app is disabled with `pm disable --user N`
            // shell-first, which works and is cheaper than a binder reflection round trip.
            runAction { reflector.setAppEnabled(packageName, !isDisabled) }
        }
    }

    /**
     * Freeze a *preinstalled* app, least destructive rung first:
     *
     *  1. **Bypass reflection** straight at `IPackageManager.setApplicationEnabledSetting`.
     *  2. **Shell** — `pm disable-user --user N <pkg>`.
     *  3. **Uninstall for this user** — only where [uninstallFreezeFallbackAllowed] permits it,
     *     which is now **nowhere**.
     *
     * Rungs 1 and 2 both live inside `Shizuku.setAppDisabled` so the reflection block has
     * exactly one copy in the codebase; [EnableRungOrder.REFLECTION_FIRST] flips its default order
     * for this path only. Both are genuinely reversible: the package keeps its data and unfreezing
     * simply re-enables it. Neither is version-gated — Shizuku users on Android 15 and below freeze
     * system apps exactly as before, they just do it without ever reaching rung 3.
     *
     * Rung 3 removes the package for this user. It ran *first* and *unconditionally* two changes
     * ago, and without `-k`, which is why freezing a preinstalled app silently cost the user their
     * data; then it ran only where the platform had refused to disable. It now does not run at all:
     * [uninstallFreezeFallbackAllowed] answers `false` for every privilege mode, so a refused
     * disable ends this method in a `Result.failure` with the package left installed, exactly as
     * `RootSystemGateway.freezeSystemApp` has ended for root all along. Removing a package is not a
     * stronger form of disabling it, and Thor no longer substitutes one for the other without being
     * asked. The rung's code stays because the gate — not this gateway — owns that decision, and
     * because the explicit "remove it for this user anyway" path that is deferred to its own change
     * is what will re-open it.
     *
     * The consequence, stated rather than hidden: on an OEM build that refuses rung 2 (Xiaomi
     * HyperOS, reported on Android 14) a Shizuku user can no longer freeze system apps at all. They
     * now get a message saying the device refused, instead of a success toast for a package that
     * had quietly been removed for them.
     *
     * Rung 3 was also **unavailable at shell uid on API 37** before it became unavailable
     * everywhere: `pm uninstall -k --user N` on a system app returns `Failure [only root can delete
     * system app for a particular user]` on Android 17, where the identical command succeeds on API
     * 36. That is why [systemFreezeFailureMessage] can name Root mode — and it is still what the
     * explicit path will meet. Read the scope of that restriction narrowly: Android 17 took away
     * *removal* at shell uid, not freezing. Rungs 1 and 2 are measurably unaffected there
     * (`pm disable-user --user 0` lands on `enabled=3` on a stock A17 build), so a stock Android 17
     * device never reached rung 3 in the first place — only an OEM that refuses rung 2 did.
     */
    private suspend fun freezeSystemApp(packageName: String): Result<Unit> {
        // Rungs 1 + 2. setAppEnabledDetailed already re-reads ApplicationInfo after each rung and
        // reports success only when the package really is disabled, so an exit code alone never
        // satisfies it. The detailed variant is used because rung 3 below turns on *why* this
        // failed, not merely that it did.
        val disable = reflector.setAppEnabledDetailed(
            packageName,
            false,
            EnableRungOrder.REFLECTION_FIRST,
        )
        if (disable.succeeded) {
            Logger.d(
                "ShizukuSystemGateway",
                "freeze($packageName): disabled in place; app data kept"
            )
            return Result.success(Unit)
        }

        // The rung-3 gate. It answers `false` for every privilege mode now, so in practice this is
        // where the chain ends — but it is still asked rather than assumed, because the gate owns
        // the rule and the explicit removal path will re-open it in one place. isSystem is true by
        // construction here and is passed explicitly for the same reason.
        if (!uninstallFreezeFallbackAllowed(
                isSystem = true,
                privilegeMode = PrivilegeMode.SHIZUKU,
                disableRefusedByPolicy = disable.refusedByPolicy,
            )
        ) {
            // Two different facts, two different sentences — and both localised. An earlier
            // revision left this branch in English on the reasoning that a non-refusal is a bug
            // report rather than a state the user acts on. That reasoning does not survive
            // MainViewModel.quickAction, which puts `e.message` straight into R.string.error_format
            // and Toasts it: both branches land in the same Toast, so localising one and not the
            // other means a Spanish user reads Spanish for a refusal and English for a bug, with
            // nothing on screen marking the difference. The diagnostic detail that justified the
            // English prose is not lost — it is in the Logger.e below, in more depth than a Toast
            // could carry.
            val refused = java.io.IOException(
                if (disable.refusedByPolicy) {
                    context.getString(R.string.freeze_system_app_disable_refused, packageName)
                } else {
                    context.getString(R.string.freeze_system_app_disable_failed, packageName)
                }
            )
            Logger.e(
                "ShizukuSystemGateway",
                "freeze($packageName): reflection and `pm disable-user` both left the package " +
                    "enabled (refusedByPolicy=${disable.refusedByPolicy}); " +
                    "`pm uninstall -k --user N` is not permitted as a substitute, so the package " +
                    "was left installed",
                refused,
            )
            return Result.failure(refused)
        }

        // Unreachable while the gate above is shut, and kept for the reason its KDoc gives: the
        // decision lives in the policy, not here, and the deferred "remove it for this user anyway"
        // path calls exactly this. RootSystemGateway.freezeSystemApp's rung 2 has been kept on the
        // same terms since root's branch went `false`.
        Logger.w(
            "ShizukuSystemGateway",
            "freeze($packageName): this device refuses to let the shell uid disable system " +
                "packages; falling back to `pm uninstall -k --user N`, which keeps the app's data"
        )
        // Deliberately NOT through runCancellableAction. That wrapper takes a Boolean and flattens
        // every falsy outcome into one sentence — "Action failed. This may happen if reflection is
        // blocked or shell lacks permissions." — which is exactly the sentence this rung must stop
        // producing: on Android 17 nothing is blocked and no permission is missing, the platform
        // simply reserves the operation for uid 0 and says so. Its cancellation discipline is kept
        // inline instead, so a ViewModel scope dying mid-freeze still unwinds rather than being
        // recorded as a failed freeze.
        val removal = try {
            reflector.freezeSystemAppForUser(packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("ShizukuSystemGateway", "freeze($packageName): uninstall-for-user threw", e)
            SystemAppRemovalOutcome(succeeded = false, exitCode = -1, platformMessage = e.message)
        }
        // Verify by re-reading rather than trusting the shell's exit code: `pm` is not a reliable
        // narrator of whether the package is still installed for this user.
        return if (isFrozen(packageName)) {
            Logger.w(
                "ShizukuSystemGateway",
                "freeze($packageName): frozen by uninstall-for-user with -k — data directories " +
                    "survive; the package stops resolving without MATCH_UNINSTALLED_PACKAGES"
            )
            Result.success(Unit)
        } else if (removal.succeeded) {
            Result.failure(Exception("Shizuku: uninstall reported success but $packageName is still active."))
        } else {
            Result.failure(Exception(systemFreezeFailureMessage(packageName, removal)))
        }
    }

    /**
     * Turn rung 3's refusal into a sentence that names the actual cause.
     *
     * Specific branch first, generic fallback last — the same shape as the "nothing refused us"
     * branch above. The specific one exists because there is exactly one refusal Thor can both
     * recognise and route around: Android 17 reserves `pm uninstall --user` on a preinstalled
     * package for uid 0 (see [ShizukuReflector.freezeSystemAppForUser]), and Thor's own Root mode
     * *is* uid 0, so "switch to Root mode" is a real instruction rather than a shrug.
     *
     * The fallback keeps the old meaning but stops throwing away the evidence: `pm`'s own output
     * rides along untranslated, so a bug report arrives with the platform's words in it instead of
     * a sentence Thor made up. When `pm` printed nothing at all there is still the exit code, which
     * is more than "Action failed" carried.
     *
     * Only the first non-blank line of that output is shown, via [displayLine], which documents why.
     * Classification still reads the *whole* message, so a refusal that arrives on a later line is
     * still recognised — hence the specific branch returning before [displayLine] is reached.
     *
     * The strings live in resources, not inline, because this text is read by the user — the same
     * reason `Shizuku.refuseUnliftableSuspension` moved its refusal into a resource in PR #330.
     */
    private fun systemFreezeFailureMessage(
        packageName: String,
        removal: SystemAppRemovalOutcome,
    ): String {
        if (isRootOnlySystemAppRemoval(removal.platformMessage)) {
            return context.getString(R.string.freeze_system_app_requires_root, packageName)
        }
        return context.getString(
            R.string.freeze_system_app_removal_failed,
            packageName,
            removal.displayLine(),
        )
    }

    /**
     * Unfreeze a *preinstalled* app, handling both mechanics that could have frozen it.
     *
     * A device in the field can be carrying either shape:
     *  - **uninstalled for this user** — FLAG_INSTALLED is clear while `enabled` stays `true`.
     *    Builds before the disable chain existed produced this shape for *every* system app on
     *    *every* release, and the build after that one still produced it wherever rung 3 fired.
     *    This build produces it nowhere, and still has to undo it everywhere;
     *  - **disabled** (rungs 1 and 2 above) — FLAG_INSTALLED is set while `enabled` is `false`.
     *
     * So: reinstall only when the package is actually missing, re-read, then enable only when it is
     * actually disabled, and finally verify the end state is installed **and** enabled — the same
     * test `AppFreezeStateReader.candidateOf` applies, so "unfrozen" here means what "not frozen"
     * means everywhere else.
     *
     * [uninstallFreezeFallbackAllowed] is deliberately **not** consulted anywhere below. It is a
     * freeze-only gate; an Android 15 device that was frozen by the old unconditional-uninstall
     * build is carrying a state this build would now refuse to create, and it still has to be able
     * to unfreeze it. Gating the thaw on the same predicate would strand exactly those users.
     */
    private suspend fun unfreezeSystemApp(packageName: String): Result<Unit> {
        // Step 1 — not installed for this user? Bring it back.
        if (!isInstalledForUser(packageName)) {
            // Called directly rather than through runAction: reinstallExistingApp awaits a
            // PackageInstaller broadcast and must stay cancellable (see runCancellableAction).
            val reported = runCancellableAction { reflector.reinstallExistingApp(packageName) }
            Logger.d(
                "ShizukuSystemGateway",
                "unfreeze($packageName): install-existing reported success=${reported.isSuccess}"
            )
            // Deliberately not returning here on either outcome. install-existing can report
            // failure and still have landed, and it restores the package with whatever enabled
            // state it had when it went away — so it can succeed and leave the app disabled. Only
            // the re-read below decides.
        }

        // Step 2 — re-read. Installed now, but disabled?
        val afterInstall = reflector.getApplicationInfoOrNull(packageName)
        if (afterInstall != null &&
            (afterInstall.flags and ApplicationInfo.FLAG_INSTALLED) != 0 &&
            !afterInstall.enabled
        ) {
            val enabled = reflector.setAppEnabled(packageName, true)
            Logger.d(
                "ShizukuSystemGateway",
                "unfreeze($packageName): re-enable reported $enabled"
            )
        }

        // Step 3 — verify the END state, not any single rung's report.
        val end = reflector.getApplicationInfoOrNull(packageName)
        val installed = end != null && (end.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        return if (installed && end.enabled) {
            Logger.d("ShizukuSystemGateway", "unfreeze($packageName): package is installed and enabled")
            Result.success(Unit)
        } else {
            Result.failure(
                Exception(
                    "Shizuku: $packageName is still frozen after unfreeze " +
                        "(installed=$installed, enabled=${end?.enabled})"
                )
            )
        }
    }

    /**
     * Is the package installed for the user Thor runs as?
     *
     * The reflector's lookup already carries MATCH_UNINSTALLED_PACKAGES, which is what lets a
     * package uninstalled-for-user resolve here at all instead of throwing. It does *not* carry
     * MATCH_DISABLED_COMPONENTS and does not need to: `getApplicationInfo` never filters on the
     * enabled setting (`AppFreezeStateReader.MATCH_FLAGS` documents the same), so a disabled
     * package resolves either way — hence no change to a default the other callers share.
     */
    private fun isInstalledForUser(packageName: String): Boolean =
        reflector.getApplicationInfoOrNull(packageName)
            ?.let { (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0 } ?: false

    /** The canonical freeze test, matching `AppFreezeStateReader.candidateOf`. */
    private fun isFrozen(packageName: String): Boolean =
        reflector.getApplicationInfoOrNull(packageName)
            ?.let { !(it.enabled && (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0) } ?: true

    override suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return runAction { reflector.setAppSuspended(packageName, isSuspended) }
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return runAction { reflector.setAppRestricted(packageName, isRestricted) }
    }

    override suspend fun rebootDevice(
        reason: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return Result.failure(Exception("Reboot requires Root. Shizuku cannot perform this action."))
    }

    override suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return if (reflector.uninstallApp(packageName)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Uninstall failed"))
        }
    }

    /**
     * Install a single APK already on disk, for the user Thor runs as.
     *
     * The `--user` is not cosmetic here. `PackageManagerShellCommand.makeInstallParams` opens with
     * `params.userId = UserHandle.USER_ALL` and leaves it there when the option loop never sees a
     * `--user`, after which the session is created with `USER_SYSTEM` plus `INSTALL_ALL_USERS` — so
     * the bare command this replaces installed the package for **every user on the device** and
     * exited 0, the mirror image of the `DELETE_ALL_USERS` trap `uninstallCommand` documents. Every
     * other privileged command in this gateway already named a user; the install path did not.
     */
    override suspend fun installApp(
        apkPath: String,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        val installerArg = preferenceRepository.getInstallerArg()

        // Through the same session builder as every other install in the app. This override has no
        // caller today, which is the only reason it never reported the bug the installer rung did:
        // `pm install <path>` is read by system_server, not by this shell, so shell-readable is
        // necessary and not sufficient. Left as `pm install` it would be a working-looking helper
        // that fails the first time someone routes the fallback chain through it.
        val file = File(apkPath)
        val command = installViaSessionCommand(
            apks = listOf(SessionApk(path = apkPath, sizeBytes = file.length(), name = file.name)),
            userId = thorUserId,
            canDowngrade = canDowngrade,
            // Caller's answer if it has one, saved setting otherwise — never `== true`, which
            // would read "no answer" as "no" and override a user who turned the setting on.
            grantAllPermissions = grantAllPermissions
                ?: preferenceRepository.shouldGrantAllPermissionsOnInstall(),
            installerArg = installerArg,
        )

        val result = ShizukuHelper.execute(command)
        return if (result.first == 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Shizuku install failed: ${result.second}"))
        }
    }

    override suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (packageName == BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))

        return try {
            val escapedPackageName = packageName.escapeForShell()

            // 1. The user this whole operation is about — read once, before the first command,
            // because the read half and the write half have to agree. `pm path` used to run bare,
            // and `PackageManagerShellCommand.runPath` seeds USER_SYSTEM, so it answered for user
            // 0's copy while the reinstall below already passed `--user`. Nothing surfaced the
            // mismatch: the APK bytes are device-wide, so both commands exit 0 whichever user they
            // name. What differed was visibility — a work-profile-only app answered nothing and
            // stopped here with "Could not find APK path", and an app installed for user 0 but not
            // for Thor's user was reinstalled off a record this user does not hold.
            val currentUser = thorUserId

            // 2. Get the APK path(s) as that user sees them
            val pathResult = ShizukuHelper.execute(pmPathCommand(escapedPackageName, currentUser))
            val paths = pathResult.second?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { it.removePrefix("package:").trim() } ?: emptyList()

            if (paths.isEmpty()) {
                return Result.failure(Exception("Could not find APK path for $packageName"))
            }

            val combinedPath = paths.joinToString(" ") { it.escapeForShell() }

            // 3. Execute the reinstallation command
            val command =
                "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
            val result = ShizukuHelper.execute(command)
            if (result.first == 0) {
                reinstallPostconditionVerifier.verify(packageName, currentUser)
            } else {
                Result.failure(Exception("Shizuku reinstall failed: ${result.second}"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Logger.e("ShizukuSystemGateway", "Reinstall with Google failed for $packageName", e)
            Result.failure(e)
        }
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Shizuku: cannot resolve the Android user for $packageName; refusing to grant on user 0."))
        val escapedPackageName = packageName.escapeForShell()
        val escapedPermissionName = permissionName.escapeForShell()
        return try {
            val result = ShizukuHelper.execute("pm grant --user $userId $escapedPackageName $escapedPermissionName")
            val grantFailure = {
                Result.failure<Unit>(
                    Exception("Shizuku: pm grant failed with exit code ${result.first}: ${result.second}")
                )
            }
            if (permissionName != GET_INSTALLED_APPS_PERMISSION) {
                return if (result.first == 0) Result.success(Unit) else grantFailure()
            }

            // The app-ops are a *parallel route* to package visibility, not a follow-up to the
            // grant, so they run whatever `pm grant` returned. On the ROMs this permission exists
            // for — MIUI/HyperOS, ColorOS, OriginOS — the AOSP `pm grant` of a vendor-defined
            // permission frequently exits non-zero while the app-op is the thing that actually
            // opens the package list, which is why installedAppsAppOpGrantCommands fires three
            // spellings of it. Gating them on the grant succeeding is what made a Chinese-ROM
            // install come back with Thor as the only visible app: the grant failed, the app-op
            // was never set, and nothing else in the app knows how to open that gate.
            val appOpTook = installedAppsAppOpGrantCommands(escapedPackageName, userId)
                .map { ShizukuHelper.execute(it) }
                .any { it.first == 0 }

            // The report follows the gate that actually opened, for every package and not just
            // Thor's own — RootSystemGateway.grantPermission holds the reasoning. Short version:
            // this method is not self-only, and restricting the fold to self-grants left a
            // third-party grant on a MIUI-class ROM writing the app-op, reporting failure, and
            // leaving the row OFF, from where the screen can only ever grant again — so nothing
            // could reach revokePermission to close the op it had just opened.
            if (result.first == 0 || appOpTook) Result.success(Unit)
            else grantFailure()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Shizuku: cannot resolve the Android user for $packageName; refusing to revoke on user 0."))
        val escapedPackageName = packageName.escapeForShell()
        val escapedPermissionName = permissionName.escapeForShell()
        return try {
            val result = ShizukuHelper.execute("pm revoke --user $userId $escapedPackageName $escapedPermissionName")

            // The revoke half of the parallel route: the app-op grant outlives `pm revoke`, so a
            // revoke that only ran `pm revoke` reported success while package visibility stayed
            // open, and nothing else in the app could close it. Issued whatever the revoke
            // returned, and deliberately not folded into the result — all three resets failing is
            // the ordinary outcome on any device that does not define this op, so reading that as a
            // failed revoke would report one on every AOSP device. `pm revoke` stays the verdict.
            if (permissionName == GET_INSTALLED_APPS_PERMISSION) {
                installedAppsAppOpRevokeCommands(escapedPackageName, userId)
                    .forEach { ShizukuHelper.execute(it) }
            }

            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku: pm revoke failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The Android user the package actually lives in.
     *
     * `pm grant`/`pm revoke` default to user 0 when no `--user` is passed, so on a work profile or a
     * Xiaomi Second Space the change would hit the primary user's same-named package instead. Derived
     * from the package's own uid rather than the foreground user, matching RootSystemGateway so a
     * permission that grants under Root grants identically under Shizuku. The reflector's lookup
     * already carries MATCH_UNINSTALLED_PACKAGES, so a frozen system app still resolves.
     *
     * Null means the package could not be resolved at all; callers must fail rather than fall back to
     * user 0, which is the original bug.
     */
    private fun getPackageUserId(packageName: String): Int? =
        reflector.getApplicationInfoOrNull(packageName)?.let { userIdOf(it.uid) }

    /**
     * Standardizes error handling for reflection and shell actions.
     *
     * Availability is already resolved by SystemRepositoryImpl.getActiveGateway() before this
     * gateway is dispatched to, so we do NOT re-probe Shizuku here (#35) — the old per-action
     * isShizukuAvailable() gate added two binder IPC (checkSelfPermission + pingBinder) to every
     * action, multiplied across every item of a batch op. If Shizuku became unavailable after
     * resolution, the action's own shell/reflection path fails (Shizuku.execute returns a
     * null-binder error → false), so the not-available case still surfaces as a Result.failure.
     */
    private suspend inline fun runAction(action: suspend () -> Boolean): Result<Unit> {
        return try {
            if (action()) Result.success(Unit)
            else Result.failure(Exception("Action failed. This may happen if reflection is blocked or shell lacks permissions."))
        } catch (e: Exception) {
            Logger.e("ShizukuSystemGateway", "Action execution failed", e)
            Result.failure(e)
        }
    }

    /**
     * [runAction] for the genuinely suspending rung, without its cancellation flaw.
     *
     * `CancellationException` IS an `Exception` in Kotlin, so [runAction]'s `catch (e: Exception)`
     * turns a cancelled action into an ordinary `Result.failure` and the caller carries on as if
     * the operation had merely failed. That matters exactly here: `reinstallExistingApp` awaits a
     * PackageInstaller broadcast under a ViewModel scope that can die mid-unfreeze. Rethrowing lets
     * the coroutine unwind cleanly, the same discipline `ShizukuReflector` keeps at its own
     * reflection call sites.
     *
     * The freeze path's rung 3 used to come through here too and no longer does — not because it
     * needed less cancellation care, but because this wrapper's `Boolean` argument cannot carry the
     * platform's own refusal text out with it. It keeps the rethrow inline instead.
     */
    private suspend inline fun runCancellableAction(action: suspend () -> Boolean): Result<Unit> {
        return try {
            if (action()) Result.success(Unit)
            else Result.failure(Exception("Action failed. This may happen if reflection is blocked or shell lacks permissions."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("ShizukuSystemGateway", "Action execution failed", e)
            Result.failure(e)
        }
    }
}