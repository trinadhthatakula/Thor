// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.Context
import android.content.pm.ApplicationInfo
import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.data.source.local.shizuku.displayLine
import com.valhalla.thor.data.source.local.SessionApk
import com.valhalla.thor.data.source.local.isEffectivelyEnabled
import com.valhalla.thor.data.source.local.installViaSessionCommand
import com.valhalla.thor.data.source.local.installedAppsAppOpGrantCommands
import com.valhalla.thor.data.source.local.installedAppsAppOpRevokeCommands
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.GET_INSTALLED_APPS_PERMISSION
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.first
import java.io.File

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

@Single
class DhizukuSystemGateway internal constructor(
    private val context: Context,
    private val reflector: DhizukuReflector,
    private val preferenceRepository: PreferenceRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : SystemGateway {

    override suspend fun isRootAvailable(
        execution: PrivilegeExecutionContext,
    ) = false

    override suspend fun isShizukuAvailable(): Boolean = false

    // DhizukuHelper.isDhizukuAvailable() performs blocking binder IPC (DhizukuAPI) and may re-bind
    // the client; confine it to IO at the gateway boundary so this probe is main-safe regardless of
    // the caller's dispatcher.
    override suspend fun isDhizukuAvailable(): Boolean = withContext(ioDispatcher) {
        DhizukuHelper.isDhizukuAvailable(context)
    }

    override suspend fun executeShellCommand(
        command: String,
        execution: PrivilegeExecutionContext,
    ): Result<Pair<Int, String?>> {
        // Runs through Dhizuku's device-owner process (DhizukuAPI.newProcess).
        return try {
            Result.success(DhizukuHelper.execute(command))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    // --- Per-component control -------------------------------------------------------------
    //
    // Empty, not partial. `DevicePolicyManager` exposes no component-enabled API of any kind — a
    // Device Owner can suspend, hide, block-uninstall and set permission policy for a *package*,
    // and none of those reach an individual class. Its only launch-related privilege is a
    // background-activity-launch exemption, which is not an export waiver:
    // `ActivityManager.canAccessUnexportedComponents` is granted to `ROOT_UID` and `SYSTEM_UID`
    // alone, and Dhizuku's shell runs as its own app uid — further from uid 0 than Shizuku's 2000,
    // not closer.
    //
    // Three explicit refusals rather than a shared helper, so that each one is visible at the site a
    // reader looks for it and none of them can be quietly turned into an unverified "try the shell
    // and see". A refusal that names the reason is the whole contribution this mode can make here.

    override suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: com.valhalla.thor.domain.gateway.ComponentEnabledState,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = Result.failure(
        Exception(context.getString(R.string.component_control_unsupported_dhizuku))
    )

    override suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = Result.failure(
        Exception(context.getString(R.string.component_control_unsupported_dhizuku))
    )

    override suspend fun stopService(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = Result.failure(
        Exception(context.getString(R.string.component_control_unsupported_dhizuku))
    )

    override suspend fun forceStopApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
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
    override suspend fun clearAllCaches(
        targetFreeBytes: Long?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        // Localized because `MainViewModel.quickAction` drops `e.message` into
        // R.string.error_format, which would otherwise put an English sentence inside a translated
        // one.
        return Result.failure(
            Exception(context.getString(R.string.clear_all_caches_unsupported_dhizuku))
        )
    }

    override suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return if (reflector.clearData(packageName)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Clear data failed. Shell pm clear and reflection both failed."))
    }

    override suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        // Device owners freeze both user and system apps by hiding them. They do not hold
        // CHANGE_COMPONENT_ENABLED_STATE, and their subprocesses do not run as shell uid.
        if (!reflector.setAppHidden(packageName, isDisabled)) {
            return Result.failure(Exception("Dhizuku: Could not change hidden state for $packageName."))
        }
        if (isDisabled) return Result.success(Unit)

        val app = reflector.getApplicationInfoOrNull(packageName)
        if (app?.isEffectivelyEnabled == true) return Result.success(Unit)

        // Keep recovery for packages disabled or removed for this user by an older build or
        // another privilege mode. Unhiding alone cannot reverse either of those states.
        return if (reflector.isSystemApp(packageName)) {
            unfreezeSystemApp(packageName)
        } else {
            reflector.setAppEnabled(packageName, true)
            if (reflector.getApplicationInfoOrNull(packageName)?.isEffectivelyEnabled == true) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Dhizuku: $packageName is still disabled after unhiding."))
            }
        }
    }

    /** Recover system apps removed or disabled by older freeze implementations. */
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
        return if (end?.isEffectivelyEnabled == true) {
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

    override suspend fun rebootDevice(
        reason: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return Result.failure(Exception("Dhizuku: Reboot not supported directly. Use Root mode instead."))
    }

    /**
     * The user-facing uninstall. Removes the app's data with it — no `-k` — because that is what
     * somebody who asked to uninstall an app wants; the freeze path has its own data-preserving
     * device-policy hide operation and must never come through here.
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
    override suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
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
    override suspend fun installApp(
        apkPath: String,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        val installerArg = preferenceRepository.getInstallerArg()

        // Through the same session builder as every other install in the app — see the note on
        // `ShizukuSystemGateway.installApp`. Dhizuku's shell has the harder version of the same
        // problem: it runs at the device-owner app's uid, so from API 30 on it cannot read another
        // app's Android/data either, and only the session rung can carry a modern device.
        val file = File(apkPath)
        val result = DhizukuHelper.execute(
            installViaSessionCommand(
                apks = listOf(
                    SessionApk(path = apkPath, sizeBytes = file.length(), name = file.name)
                ),
                userId = thorUserId,
                canDowngrade = canDowngrade,
                // Caller's answer if it has one, saved setting otherwise — never `== true`, which
                // would read "no answer" as "no" and override a user who turned the setting on.
                grantAllPermissions = grantAllPermissions
                    ?: preferenceRepository.shouldGrantAllPermissionsOnInstall(),
                installerArg = installerArg,
            )
        )
        return if (result.first == 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Dhizuku: Install failed: ${result.second}"))
        }
    }

    override suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (packageName == com.valhalla.thor.BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))

        // Device-owner installs are allowed, but attributing them to another UID requires
        // INSTALL_PACKAGES. Both pm and wrapped PackageInstaller sessions reject Play's name;
        // set-installer also requires Play's signing certificate. Do not reinstall as Dhizuku
        // and report that as Fix Store success. Keep this guard for callers outside the UI too.
        return Result.failure(
            UiTextException(UiText.StringResource(R.string.fix_store_unsupported_dhizuku))
        )
    }

    override suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return if (reflector.setAppSuspended(packageName, isSuspended)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Set suspended state failed."))
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return if (reflector.setAppRestricted(packageName, isRestricted)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Set restricted state failed."))
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
