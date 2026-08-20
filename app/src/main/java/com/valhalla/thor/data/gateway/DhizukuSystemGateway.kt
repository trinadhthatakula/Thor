// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.Context
import android.content.pm.ApplicationInfo
import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.data.source.local.shizuku.SystemAppRemovalOutcome
import com.valhalla.thor.data.source.local.shizuku.displayLine
import com.valhalla.thor.data.source.local.shizuku.isRootOnlySystemAppRemoval
import com.valhalla.thor.data.source.local.installCommand
import com.valhalla.thor.data.source.local.installedAppsAppOpGrantCommands
import com.valhalla.thor.data.source.local.installedAppsAppOpRevokeCommands
import com.valhalla.thor.data.source.local.pmPathCommand
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.GET_INSTALLED_APPS_PERMISSION
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.uninstallFreezeFallbackAllowed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.first

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

@Single
class DhizukuSystemGateway(
    // Two uses: the system-app freeze's refusal message is read by the user, so it has to come out
    // of resources (ShizukuSystemGateway and RootSystemGateway take theirs the same way), and the
    // availability probe binds the Dhizuku client through it — see DhizukuHelper.isDhizukuAvailable.
    private val context: Context,
    private val reflector: DhizukuReflector,
    private val preferenceRepository: PreferenceRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override suspend fun isShizukuAvailable(): Boolean = false

    // DhizukuHelper.isDhizukuAvailable() performs blocking binder IPC (DhizukuAPI) and may re-bind
    // the client; confine it to IO at the gateway boundary so this probe is main-safe regardless of
    // the caller's dispatcher.
    override suspend fun isDhizukuAvailable(): Boolean = withContext(ioDispatcher) {
        DhizukuHelper.isDhizukuAvailable(context)
    }

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> {
        // Runs through Dhizuku's device-owner process (DhizukuAPI.newProcess).
        return runCatching { DhizukuHelper.execute(command) }
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return if (reflector.forceStop(packageName)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Force stop failed. Shell command and reflection both denied."))
    }

    /**
     * Always a failure, and that is the correction rather than a gap.
     *
     * `pm trim-caches` is a shell command, and Dhizuku has no shell: [executeShellCommand] runs
     * through the Dhizuku app's own uid, which `PackageManagerShellCommand` refuses. The device
     * owner API has no cache-clearing member at all — `DevicePolicyManager` can wipe a profile, not
     * a cache — and the reflective `deleteApplicationCacheFiles*` rung this gateway used to carry
     * died in a double-wrapped binder belonging to a privilege mode the user had not set up. Three
     * doors, all shut, so this says so in a sentence the user can act on instead of failing with a
     * shell error that reads like a bug.
     */
    override suspend fun clearAllCaches(targetFreeBytes: Long?): Result<Unit> {
        // Out of resources, for the same reason the system-app freeze refusal below is: the user
        // reads this one and acts on it. `MainViewModel.quickAction` drops `e.message` into
        // R.string.error_format, which would otherwise put an English sentence inside a translated
        // one.
        return Result.failure(
            Exception(context.getString(R.string.clear_all_caches_unsupported_dhizuku))
        )
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> {
        return if (reflector.clearData(packageName)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Clear data failed. Shell pm clear and reflection both failed."))
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        // FLAG_SYSTEM alone, never OR'd with FLAG_UPDATED_SYSTEM_APP — DhizukuReflector.isSystemApp
        // is written that way, matching AppInfoMapper, AppFreezeStateReader.candidateOf and both
        // other gateways. The destructive-fallback gate below is keyed on this same answer, so a
        // second definition of "system" here would gate a different set of apps than the freeze
        // itself acts on.
        val isSystem = reflector.isSystemApp(packageName)
        return if (isSystem) {
            if (isDisabled) freezeSystemApp(packageName) else unfreezeSystemApp(packageName)
        } else {
            // Unchanged: a user app disables through the ordinary rung chain, which keeps its data
            // and needs no fallback.
            if (reflector.setAppEnabled(packageName, !isDisabled)) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: Set enabled state failed. Shell and reflection both failed."))
        }
    }

    /**
     * Freeze a *preinstalled* app, least destructive rung first:
     *
     *  1. **Disable** — `pm disable-user --user N`, then `IPackageManager` reflection, then the
     *     unprivileged `PackageManager`, each verified by a re-read
     *     ([DhizukuReflector.setAppEnabledDetailed]). The package stays installed and keeps its
     *     data, and unfreezing simply re-enables it.
     *  2. **Uninstall for this user with `-k`** — only where [uninstallFreezeFallbackAllowed]
     *     permits it, which is now **nowhere**.
     *
     * Rung 1 did not exist here two changes ago: every system-app freeze went straight to
     * `pm uninstall --user N`, **without `-k`**, so it destroyed the app's data and judged itself
     * on `pm`'s exit code. That is the defect this method exists to remove.
     *
     * Rung 2 then ran only where rung 1 had been *refused* by the platform. It now runs nowhere:
     * [uninstallFreezeFallbackAllowed] answers `false` for every privilege mode, so a refused
     * disable ends this method in a `Result.failure` with the package left installed. Removing a
     * package for the user is not a stronger form of disabling it, and it is not Thor's to
     * substitute unasked. The rung's code stays because the policy — not this gateway — owns that
     * decision, and because the deferred "remove it for this user anyway" path calls exactly it.
     *
     * **Rung 1 is unverified on hardware.** No device with Dhizuku installed was available, and the
     * measurements that exist were taken at shell uid, which is not the identity Dhizuku's commands
     * run as — `DhizukuAPI.newProcess` spawns `pm` inside the device-owner app. So rung 1 is an
     * attempt, not a promise. What used to sit behind it was rung 2; what sits behind it now is an
     * honest failure naming which of the two things happened. If the device-owner identity turns
     * out not to be allowed to disable a system package, the user is told that rather than having
     * the package removed for them.
     *
     * The residual risk of that arrangement, stated rather than hidden: a device that refuses rung 1
     * *without* a SecurityException (silently ignoring the change, say) fails the freeze with the
     * less specific of the two messages. The fix for such a device is to widen what counts as a
     * refusal, not to reopen the fallback.
     */
    private fun freezeSystemApp(packageName: String): Result<Unit> {
        // Already frozen — by us, by an older build, or by another tool. Short-circuit before any
        // rung runs, matching RootSystemGateway.freezeSystemApp: re-freezing a package that is
        // merely disabled must never walk down into the uninstall rung just because the first
        // command reported nothing to do.
        if (isFrozen(packageName)) {
            Logger.i("DhizukuSystemGateway", "freeze($packageName): already frozen, no rung run")
            return Result.success(Unit)
        }

        // Rung 1. setAppEnabledDetailed re-reads ApplicationInfo after each of its own rungs and
        // reports success only when the package really is disabled, so an exit code alone never
        // satisfies it. The detailed variant is used because rung 2 turns on *why* this failed,
        // not merely that it did.
        val disable = reflector.setAppEnabledDetailed(packageName, false)
        if (disable.succeeded) {
            Logger.d(
                "DhizukuSystemGateway",
                "freeze($packageName): disabled in place; app data kept"
            )
            return Result.success(Unit)
        }

        // The rung-2 gate. It answers `false` for every privilege mode now, so in practice this is
        // where the chain ends — but it is still asked rather than assumed, because the gate owns
        // the rule and the explicit removal path will re-open it in one place. isSystem is true by
        // construction here and is passed explicitly for the same reason.
        if (!uninstallFreezeFallbackAllowed(
                isSystem = true,
                privilegeMode = PrivilegeMode.DHIZUKU,
                disableRefusedByPolicy = disable.refusedByPolicy,
            )
        ) {
            // Two different facts, two different sentences — the same split ShizukuSystemGateway
            // makes, and deliberately the same two resources, because neither sentence depends on
            // which identity asked: the refusal is the platform's, and the non-refusal names Thor.
            // See that gateway for why both are localised rather than only the refusal.
            val refused = java.io.IOException(
                if (disable.refusedByPolicy) {
                    context.getString(R.string.freeze_system_app_disable_refused, packageName)
                } else {
                    context.getString(R.string.freeze_system_app_disable_failed, packageName)
                }
            )
            Logger.e(
                "DhizukuSystemGateway",
                "freeze($packageName): every disable rung ran and the package is still enabled " +
                    "(refusedByPolicy=${disable.refusedByPolicy}); `pm uninstall -k --user N` is " +
                    "not permitted as a substitute, so the package was left installed",
                refused,
            )
            return Result.failure(refused)
        }

        // Unreachable while the gate above is shut, and kept for the reason its KDoc gives: the
        // decision lives in the policy, not here, and the deferred "remove it for this user anyway"
        // path calls exactly this.
        Logger.w(
            "DhizukuSystemGateway",
            "freeze($packageName): this device refuses to let Dhizuku's device-owner identity " +
                "disable system packages; falling back to `pm uninstall -k --user N`, which keeps " +
                "the app's data"
        )
        val removal = reflector.freezeSystemAppForUser(packageName)
        // Verify by re-reading rather than trusting the exit code: `pm` is not a reliable narrator
        // of whether the package is still installed for this user, in either direction.
        return if (isFrozen(packageName)) {
            Logger.w(
                "DhizukuSystemGateway",
                "freeze($packageName): frozen by uninstall-for-user with -k — data directories " +
                    "survive; the package stops resolving without MATCH_UNINSTALLED_PACKAGES"
            )
            Result.success(Unit)
        } else if (removal.succeeded) {
            Result.failure(Exception("Dhizuku: uninstall reported success but $packageName is still active."))
        } else {
            Result.failure(Exception(systemFreezeFailureMessage(packageName, removal)))
        }
    }

    /**
     * Turn rung 2's refusal into a sentence that names the actual cause.
     *
     * Deliberately the same two strings `ShizukuSystemGateway.systemFreezeFailureMessage` uses, and
     * they are worded about the *platform* rather than about a privilege mode, because the refusal
     * is the platform's and is identical under both: Android 17 reserves `pm uninstall --user` on a
     * preinstalled package for uid 0, and neither the shell uid nor Dhizuku's device-owner app is
     * uid 0. Thor's Root mode is, so "switch to Root mode" is a real instruction rather than a
     * shrug. Not hoisted into a shared helper only because the two gateways share no base class
     * today; if a third one needs it, hoist it then.
     *
     * The fallback branch keeps the old meaning but stops throwing away the evidence: `pm`'s own
     * output rides along untranslated, so a bug report arrives with the platform's words in it —
     * but only its first non-blank line, via [displayLine]. That is not cosmetic here:
     * `DhizukuHelper.execute` folds *every* thrown failure into `stackTraceToString()`, and its
     * device-owner binder dying mid-freeze is the ordinary way this branch is reached, so passing
     * the message through whole puts a multi-kilobyte stack trace in the snackbar.
     *
     * Classification reads the *whole* message and runs first, for the reason [displayLine] gives.
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
     *    Dhizuku builds before the disable chain existed produced this shape for *every* system
     *    app, and the build after that one still produced it wherever rung 2 fired. This build
     *    produces it nowhere, and still has to undo it everywhere;
     *  - **disabled** (rung 1 above) — FLAG_INSTALLED is set while `enabled` is `false`.
     *
     * So: reinstall only when the package is actually missing, re-read, then enable only when it is
     * actually disabled, and finally verify the end state is installed **and** enabled — the same
     * test `AppFreezeStateReader.candidateOf` applies, so "unfrozen" here means what "not frozen"
     * means everywhere else.
     *
     * What this replaces judged the whole operation on `pm install-existing`'s exit code and threw
     * away `setAppEnabled`'s result entirely, so a package that came back installed but still
     * disabled was reported as unfrozen. It also enabled *before* reinstalling, which is backwards:
     * `install-existing` restores the package with whatever enabled state it had when it went away,
     * so it can undo the enable that preceded it.
     *
     * [uninstallFreezeFallbackAllowed] is deliberately **not** consulted anywhere below. It is a
     * freeze-only gate; every Dhizuku user carrying a system app frozen by the old unconditional
     * uninstall has to be able to thaw it, including on devices this build would now refuse to
     * freeze that way.
     */
    private fun unfreezeSystemApp(packageName: String): Result<Unit> {
        // Step 1 — not installed for this user? Bring it back.
        if (!reflector.isAppInstalled(packageName)) {
            val reported = reflector.reinstallExistingApp(packageName)
            Logger.d(
                "DhizukuSystemGateway",
                "unfreeze($packageName): install-existing reported success=$reported"
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
            Logger.d("DhizukuSystemGateway", "unfreeze($packageName): re-enable reported $enabled")
        }

        // Step 3 — verify the END state, not any single rung's report. An app that was already
        // unfrozen when we arrived lands here having run nothing, and passes: that is a success.
        val end = reflector.getApplicationInfoOrNull(packageName)
        val installed = end != null && (end.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        return if (installed && end.enabled) {
            Logger.d(
                "DhizukuSystemGateway",
                "unfreeze($packageName): package is installed and enabled"
            )
            Result.success(Unit)
        } else {
            Result.failure(
                Exception(
                    "Dhizuku: $packageName is still frozen after unfreeze " +
                        "(installed=$installed, enabled=${end?.enabled})"
                )
            )
        }
    }

    /**
     * The canonical freeze test, matching `AppFreezeStateReader.candidateOf`: frozen unless the
     * package is BOTH enabled AND installed for this user.
     *
     * An unreadable package answers `true` here — a package that stopped resolving even with
     * MATCH_UNINSTALLED_PACKAGES is at least as gone as a frozen one, and the freeze path must not
     * run a destructive rung against a state it cannot see.
     */
    private fun isFrozen(packageName: String): Boolean =
        reflector.getApplicationInfoOrNull(packageName)
            ?.let { !(it.enabled && (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0) } ?: true

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        return Result.failure(Exception("Dhizuku: Reboot not supported directly. Use Root mode instead."))
    }

    /**
     * The user-facing uninstall. Removes the app's data with it — no `-k` — because that is what
     * somebody who asked to uninstall an app wants; the freeze path has its own data-preserving
     * entry point ([DhizukuReflector.freezeSystemAppForUser]) and must never come through here.
     *
     * Judged on FLAG_INSTALLED rather than on `pm`'s exit code, which lies in both directions, and
     * the platform's own words are carried into the failure instead of "Uninstall failed."
     *
     * "`pm` said yes and the package is still here" gets its own sentence rather than being folded
     * into the generic one, the same way the freeze path above splits it. Without the split, `pm`'s
     * own word — `Success` — is pasted into a sentence beginning "Uninstall failed", which reads as
     * nonsense and hides what happened. It is reachable: removing an *updated* system app takes the
     * update off and leaves the factory version installed, so `pm` exits 0 and FLAG_INSTALLED never
     * clears. Reporting failure there is still right — the app is on the device and the caller
     * falls back to the platform's own uninstall dialog — the message just has to say so.
     */
    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        val removal = reflector.uninstallApp(packageName)
        if (!reflector.isAppInstalled(packageName)) return Result.success(Unit)
        if (removal.succeeded) {
            return Result.failure(
                Exception(
                    "Dhizuku: uninstall reported success but $packageName is still installed " +
                        "for this user."
                )
            )
        }
        return Result.failure(Exception("Dhizuku: Uninstall failed — ${removal.displayLine()}"))
    }

    /**
     * Install a single APK already on disk, for the user Thor runs as.
     *
     * Naming the user is what stops this installing for all of them. `makeInstallParams` leaves
     * `params.userId` at `UserHandle.USER_ALL` when the option loop sees no `--user`, and the
     * session is then created with `USER_SYSTEM` plus `INSTALL_ALL_USERS`: the bare command this
     * replaces installed the package for **every user on the device** and exited 0, the same
     * all-users widening `DhizukuHelper.uninstallApp` avoids from the removal side.
     *
     * Running as the Device Owner does not change that. `DhizukuAPI.newProcess` decides which
     * process `pm` runs in; the missing `--user` is interpreted by `PackageManagerService`, which
     * neither knows nor cares who invoked the command.
     */
    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        val installerArg = preferenceRepository.getInstallerArg()

        val result = DhizukuHelper.execute(
            installCommand(
                escapedApkPaths = listOf(apkPath.escapeForShell()),
                userId = thorUserId,
                canDowngrade = canDowngrade,
                installerArg = installerArg,
            )
        )
        return if (result.first == 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Dhizuku: Install failed: ${result.second}"))
        }
    }

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        if (packageName == com.valhalla.thor.BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))

        return try {
            val escapedPackageName = packageName.escapeForShell()

            // 1. The user this whole operation is about — Thor's own, matching every other `--user`
            // here, and read before the first command rather than between the two. `pm path` used
            // to run bare, and `PackageManagerShellCommand.runPath` seeds USER_SYSTEM, so the read
            // half answered for user 0 while the write half below already named Thor's user. The
            // APK bytes are device-wide, so both commands exit 0 either way and the mismatch is
            // invisible: what a user id selects here is whether the package is *visible*, which is
            // how a work-profile-only app came back with no paths at all.
            val currentUser = thorUserId

            // 2. Get the APK path(s) as that user sees them
            val pathResult = DhizukuHelper.execute(pmPathCommand(escapedPackageName, currentUser))
            val paths = pathResult.second?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { it.removePrefix("package:").trim() } ?: emptyList()

            if (paths.isEmpty()) {
                return Result.failure(Exception("Dhizuku: Could not find APK path for $packageName"))
            }

            val combinedPath = paths.joinToString(" ") { it.escapeForShell() }

            // 3. Execute the reinstallation command
            val command =
                "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
            val result = DhizukuHelper.execute(command)
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: Reinstall failed: ${result.second}"))
        } catch (e: Exception) {
            Logger.e("DhizukuSystemGateway", "Reinstall with Google failed for $packageName", e)
            Result.failure(e)
        }
    }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> {
        return if (reflector.setAppSuspended(packageName, isSuspended)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Set suspended state failed."))
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> {
        return if (reflector.setAppRestricted(packageName, isRestricted)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Set restricted state failed."))
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Dhizuku: cannot resolve the Android user for $packageName; refusing to grant on user 0."))
        val escapedPackageName = packageName.escapeForShell()
        val escapedPermissionName = permissionName.escapeForShell()
        return try {
            val result = DhizukuHelper.execute("pm grant --user $userId $escapedPackageName $escapedPermissionName")
            val grantFailure = {
                Result.failure<Unit>(
                    Exception("Dhizuku: pm grant failed with exit code ${result.first}: ${result.second}")
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
                .map { DhizukuHelper.execute(it) }
                .any { it.first == 0 }

            // Whose grant this is decides what may count as success. This method is not self-only:
            // PermissionManagerScreen -> TogglePermissionUseCase reaches it for arbitrary
            // third-party packages, and that screen's row shows the *runtime permission*, flipped
            // optimistically without a re-read, so folding an app-op success into "granted" there
            // would leave the row disagreeing with PackageManager.checkPermission. For Thor's own
            // package the routes are interchangeable — SelfPermissionGranter re-reads
            // checkSelfPermission and acts on the outcome. The app-ops are issued either way.
            val isSelfGrant = packageName == context.packageName
            if (result.first == 0 || (isSelfGrant && appOpTook)) Result.success(Unit)
            else grantFailure()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Dhizuku: cannot resolve the Android user for $packageName; refusing to revoke on user 0."))
        val escapedPackageName = packageName.escapeForShell()
        val escapedPermissionName = permissionName.escapeForShell()
        return try {
            val result = DhizukuHelper.execute("pm revoke --user $userId $escapedPackageName $escapedPermissionName")

            // The revoke half of the parallel route: the app-op grant outlives `pm revoke`, so a
            // revoke that only ran `pm revoke` reported success while package visibility stayed
            // open, and nothing else in the app could close it. Issued whatever the revoke
            // returned, and deliberately not folded into the result — all three resets failing is
            // the ordinary outcome on any device that does not define this op, so reading that as a
            // failed revoke would report one on every AOSP device. `pm revoke` stays the verdict.
            if (permissionName == GET_INSTALLED_APPS_PERMISSION) {
                installedAppsAppOpRevokeCommands(escapedPackageName, userId)
                    .forEach { DhizukuHelper.execute(it) }
            }

            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: pm revoke failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The Android user the package actually lives in.
     *
     * `pm grant`/`pm revoke` default to user 0 when no `--user` is passed, so on a work profile or a
     * Xiaomi Second Space the change would hit the primary user's same-named package instead. Derived
     * from the package's own uid, matching the Root and Shizuku gateways — a permission must not
     * grant under one privilege mode and quietly miss under another.
     *
     * Dhizuku runs these as the Device Owner (user 0), which is not guaranteed to hold
     * INTERACT_ACROSS_USERS, so a cross-user `--user` may be refused. That is the intended outcome:
     * `pm` reports a real failure instead of silently mutating user 0's copy of the package. For the
     * ordinary same-user case `--user <id>` is exactly what the bare command already did, and Dhizuku
     * already passes `--user` on its install/uninstall paths.
     *
     * Null means the package could not be resolved at all; callers must fail rather than fall back to
     * user 0, which is the original bug.
     */
    private fun getPackageUserId(packageName: String): Int? =
        reflector.getApplicationInfoOrNull(packageName)?.let { userIdOf(it.uid) }

}
