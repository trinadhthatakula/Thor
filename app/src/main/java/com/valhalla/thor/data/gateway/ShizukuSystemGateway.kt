// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.EnableRungOrder
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.uninstallFreezeFallbackAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import rikka.shizuku.Shizuku
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.first

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
private val USER_ID_REGEX = Regex("^\\d+$")

@Single
class ShizukuSystemGateway(
    private val reflector: ShizukuReflector,
    private val preferenceRepository: PreferenceRepository
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    // Shizuku.checkSelfPermission()/pingBinder() are blocking binder IPC; confine them to IO
    // at the gateway boundary so this probe is main-safe regardless of the caller's dispatcher.
    override suspend fun isShizukuAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun isDhizukuAvailable(): Boolean = false

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> {
        // Runs through Shizuku's privileged process (shell uid), same path as in-app actions.
        return runCatching { ShizukuHelper.execute(command) }
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return runAction { reflector.forceStop(packageName) }
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        return runAction { reflector.clearCache(packageName) }
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> {
        return runAction { reflector.clearData(packageName) }
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
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
     *  3. **Uninstall for this user** — only where [uninstallFreezeFallbackAllowed] permits it.
     *
     * Rungs 1 and 2 both live inside `Shizuku.setAppDisabled` so the reflection block has
     * exactly one copy in the codebase; [EnableRungOrder.REFLECTION_FIRST] flips its default order
     * for this path only. Both are genuinely reversible: the package keeps its data and unfreezing
     * simply re-enables it. Neither is version-gated — Shizuku users on Android 15 and below freeze
     * system apps exactly as before, they just do it without ever reaching rung 3.
     *
     * Rung 3 removes the package for this user. It ran *first* and *unconditionally* before this
     * change, and without `-k`, which is why freezing a preinstalled app silently cost the user
     * their data. It now carries `-k` ([ShizukuReflector.freezeSystemAppForUser]) so the data
     * directories survive, and it is reached only where the platform actually refused to disable —
     * everywhere else a failure to disable stays a failure. It is still last: it is the only rung
     * that changes what the app looks like to the rest of the system (`FLAG_INSTALLED` clears).
     *
     * Rung 3 is also **unavailable at shell uid on API 37**: `pm uninstall -k --user N` on a system
     * app returns `Failure [only root can delete system app for a particular user]` on Android 17,
     * where the identical command succeeds on API 36. The package is left untouched, so the chain
     * ends in an honest failure rather than a wrong state.
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

        // Rung 3, gated on the platform having actually refused — not on the Android version.
        // A stock AOSP API 36 emulator disables system apps from the shell uid without complaint,
        // so a version test would uninstall the package for the user on every device that never
        // needed this rung — clearing FLAG_INSTALLED, and with it the package's visibility to every
        // query that omits MATCH_UNINSTALLED_PACKAGES, for no reason — while still missing the
        // OEM builds (Xiaomi HyperOS, reported on Android 14) that do. isSystem
        // is true by construction here, but it is passed explicitly so the gate — not this call
        // site — owns the whole rule.
        if (!uninstallFreezeFallbackAllowed(
                isSystem = true,
                privilegeMode = PrivilegeMode.SHIZUKU,
                disableRefusedByPolicy = disable.refusedByPolicy,
            )
        ) {
            Logger.e(
                "ShizukuSystemGateway",
                "freeze($packageName): reflection and `pm disable-user` both left the package " +
                    "enabled, and nothing refused us — this is a failure to report, not a platform " +
                    "limit to work around"
            )
            return Result.failure(
                Exception(
                    "Shizuku could not disable the system app $packageName. Nothing refused the " +
                        "request, so this is not a device restriction — reporting the failure " +
                        "rather than removing the app for this user."
                )
            )
        }

        Logger.w(
            "ShizukuSystemGateway",
            "freeze($packageName): this device refuses to let the shell uid disable system " +
                "packages; falling back to `pm uninstall -k --user N`, which keeps the app's data"
        )
        val reported = runCancellableAction { reflector.freezeSystemAppForUser(packageName) }
        // Verify by re-reading rather than trusting the shell's exit code: `pm` is not a reliable
        // narrator of whether the package is still installed for this user.
        return if (isFrozen(packageName)) {
            Logger.w(
                "ShizukuSystemGateway",
                "freeze($packageName): frozen by uninstall-for-user with -k — data directories and " +
                    "runtime permission grants both survive the round trip (measured on API 36)"
            )
            Result.success(Unit)
        } else {
            reported.fold(
                onSuccess = {
                    Result.failure(Exception("Shizuku: uninstall reported success but $packageName is still active."))
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Unfreeze a *preinstalled* app, handling both mechanics that could have frozen it.
     *
     * A device in the field can be carrying either shape:
     *  - **uninstalled for this user** — FLAG_INSTALLED is clear while `enabled` stays `true`.
     *    Every build before this one produced this shape for *every* system app on *every* release;
     *    this build still produces it, but only through the gated rung 3 above;
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

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> {
        return runAction { reflector.setAppSuspended(packageName, isSuspended) }
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> {
        return runAction { reflector.setAppRestricted(packageName, isRestricted) }
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        return Result.failure(Exception("Reboot requires Root. Shizuku cannot perform this action."))
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        return if (reflector.uninstallApp(packageName)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Uninstall failed"))
        }
    }

    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        val installerArg = preferenceRepository.getInstallerArg()
        
        val command = "pm install -r -g${if (canDowngrade) " -d" else ""}$installerArg ${
            apkPath.escapeForShell()
        }"
        
        val result = ShizukuHelper.execute(command)
        return if (result.first == 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Shizuku install failed: ${result.second}"))
        }
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        return 0L // Requires specialized logic
    }

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        if (packageName == BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))

        return try {
            val escapedPackageName = packageName.escapeForShell()
            // 1. Get the APK path(s)
            val pathResult = ShizukuHelper.execute("pm path $escapedPackageName")
            val paths = pathResult.second?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { it.removePrefix("package:").trim() } ?: emptyList()

            if (paths.isEmpty()) {
                return Result.failure(Exception("Could not find APK path for $packageName"))
            }

            val combinedPath = paths.joinToString(" ") { it.escapeForShell() }

            // 2. Get Current User ID
            val currentUser = ShizukuHelper.getCurrentUserId()

            // 3. Execute the reinstallation command
            val command =
                "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
            val result = ShizukuHelper.execute(command)
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku reinstall failed: ${result.second}"))
        } catch (e: Exception) {
            Logger.e("ShizukuSystemGateway", "Reinstall with Google failed for $packageName", e)
            Result.failure(e)
        }
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
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
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku: pm grant failed with exit code ${result.first}: ${result.second}"))
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
            ?: return Result.failure(Exception("Shizuku: cannot resolve the Android user for $packageName; refusing to revoke on user 0."))
        val escapedPackageName = packageName.escapeForShell()
        val escapedPermissionName = permissionName.escapeForShell()
        return try {
            val result = ShizukuHelper.execute("pm revoke --user $userId $escapedPackageName $escapedPermissionName")
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
     * [runAction] for the two genuinely suspending rungs, without its cancellation flaw.
     *
     * `CancellationException` IS an `Exception` in Kotlin, so [runAction]'s `catch (e: Exception)`
     * turns a cancelled action into an ordinary `Result.failure` and the caller carries on as if
     * the operation had merely failed. That matters exactly here: `uninstallApp` polls with
     * `delay()` and `reinstallExistingApp` awaits a PackageInstaller broadcast, both under a
     * ViewModel scope that can die mid-freeze. Rethrowing lets the coroutine unwind cleanly, the
     * same discipline `ShizukuReflector` keeps at its own reflection call sites.
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