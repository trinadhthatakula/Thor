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
import com.valhalla.thor.data.source.local.pmPathCommand
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.uninstallFreezeFallbackAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val preferenceRepository: PreferenceRepository
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override suspend fun isShizukuAvailable(): Boolean = false

    // DhizukuHelper.isDhizukuAvailable() performs blocking binder IPC (DhizukuAPI) and may re-bind
    // the client; confine it to IO at the gateway boundary so this probe is main-safe regardless of
    // the caller's dispatcher.
    override suspend fun isDhizukuAvailable(): Boolean = withContext(Dispatchers.IO) {
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

    override suspend fun clearCache(packageName: String): Result<Unit> {
        return if (reflector.clearCache(packageName)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Clear cache failed. System reflection and shell rm -rf both failed."))
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
     *     permits it, i.e. only where rung 1 was *refused* by the platform rather than merely
     *     failing.
     *
     * Rung 1 did not exist here before this change: every system-app freeze went straight to
     * `pm uninstall --user N`, **without `-k`**, so it destroyed the app's data and judged itself
     * on `pm`'s exit code. That is the defect this method exists to remove.
     *
     * **Rung 1 is unverified on hardware.** No device with Dhizuku installed was available, and the
     * measurements that exist were taken at shell uid, which is not the identity Dhizuku's commands
     * run as — `DhizukuAPI.newProcess` spawns `pm` inside the device-owner app. So rung 1 is an
     * attempt, not a promise, and rung 2 deliberately stays reachable behind it: if the device-owner
     * identity turns out not to be allowed to disable a system package, `PackageManagerService`
     * answers with a `SecurityException`, the chain reports `refusedByPolicy`, and the freeze still
     * happens the way it always did — minus the data loss.
     *
     * The residual risk of that arrangement, stated rather than hidden: a device that refuses rung 1
     * *without* a SecurityException (silently ignoring the change, say) now fails the freeze instead
     * of escalating. The fix for such a device is to widen what counts as a refusal, not to reopen
     * the ungated fallback — an ungated fallback is what made a binder timeout cost a user their
     * app data.
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

        // Rung 2, gated on the platform having actually refused — not on the Android version and
        // not on "the first thing did not work". isSystem is true by construction here, but it is
        // passed explicitly so the gate — not this call site — owns the whole rule.
        if (!uninstallFreezeFallbackAllowed(
                isSystem = true,
                privilegeMode = PrivilegeMode.DHIZUKU,
                disableRefusedByPolicy = disable.refusedByPolicy,
            )
        ) {
            Logger.e(
                "DhizukuSystemGateway",
                "freeze($packageName): every disable rung ran and the package is still enabled, " +
                    "and nothing refused us — this is a failure to report, not a platform limit " +
                    "to work around"
            )
            return Result.failure(
                Exception(
                    "Dhizuku could not disable the system app $packageName. Nothing refused the " +
                        "request, so this is not a device restriction — reporting the failure " +
                        "rather than removing the app for this user."
                )
            )
        }

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
     *    Every Dhizuku build before this one produced this shape for *every* system app; this build
     *    still produces it, but only through the gated rung 2 above;
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

    override suspend fun getAppCacheSize(packageName: String): Long {
        return 0L
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
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: pm grant failed with exit code ${result.first}: ${result.second}"))
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
