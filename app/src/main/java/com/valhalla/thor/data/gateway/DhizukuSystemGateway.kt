// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.data.source.local.dhizuku.OWNER_OPERATION_TIMEOUT_MS
import com.valhalla.thor.data.source.local.dhizuku.uninstallVerified
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
    ): Result<Unit> = Result.failure(
        UiTextException(UiText.StringResource(R.string.force_stop_unsupported_dhizuku))
    )

    /** The owner UID lacks cache-trimming authority, and DPM has no cache-only clearing API. */
    override suspend fun clearAllCaches(
        targetFreeBytes: Long?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return Result.failure(
            UiTextException(UiText.StringResource(R.string.clear_all_caches_unsupported_dhizuku))
        )
    }

    override suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        if (reflector.clearData(packageName, execution.ownerOperationTimeoutMillis())) Result.success(Unit)
        else Result.failure(UiTextException(UiText.StringResource(R.string.dhizuku_clear_data_failed)))
    }

    override suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        // Device owners freeze both user and system apps by hiding them. They do not hold
        // CHANGE_COMPONENT_ENABLED_STATE, and their subprocesses do not run as shell uid.
        if (!reflector.setAppHidden(packageName, isDisabled)) {
            Logger.e(
                "DhizukuSystemGateway",
                "Could not change hidden state for $packageName to $isDisabled"
            )
            return Result.failure(
                UiTextException(UiText.StringResource(
                    if (isDisabled) R.string.dhizuku_freeze_failed
                    else R.string.dhizuku_unfreeze_failed
                ))
            )
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
                Logger.e("DhizukuSystemGateway", "$packageName is still disabled after unhiding")
                Result.failure(
                    UiTextException(UiText.StringResource(R.string.dhizuku_unfreeze_failed))
                )
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
            Logger.e(
                "DhizukuSystemGateway",
                "$packageName is still frozen after unfreeze " +
                    "(installed=$installed, enabled=${end?.enabled})"
            )
            Result.failure(
                UiTextException(UiText.StringResource(R.string.dhizuku_unfreeze_failed))
            )
        }
    }

    override suspend fun rebootDevice(
        reason: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return Result.failure(Exception("Dhizuku: Reboot not supported directly. Use Root mode instead."))
    }

    /** Await this operation's result and a readable absent state; a query failure is not absence. */
    override suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        val removal = reflector.uninstallApp(packageName, execution.ownerOperationTimeoutMillis())
        val installed = reflector.readInstalledState(packageName)
        if (uninstallVerified(removal.succeeded, installed)) {
            Result.success(Unit)
        } else {
            Logger.e(
                "DhizukuSystemGateway",
                "Uninstall $packageName failed: status=${removal.exitCode}, " +
                    "detail=${removal.platformMessage}, installed=${installed.getOrNull()}",
                installed.exceptionOrNull(),
            )
            Result.failure(UiTextException(UiText.StringResource(R.string.dhizuku_uninstall_failed)))
        }
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
    ): Result<Unit> = changePermission(packageName, permissionName, granted = true)

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = changePermission(packageName, permissionName, granted = false)

    private suspend fun changePermission(
        packageName: String,
        permissionName: String,
        granted: Boolean,
    ): Result<Unit> = withContext(ioDispatcher) {
        fun failure(): Result<Unit> = Result.failure(
            UiTextException(UiText.StringResource(R.string.failed_to_modify_permission))
        )
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return@withContext failure()
        }
        // DPM acts on its owner's user. Never let an unresolved/cross-user package select user 0.
        val userId = reflector.getApplicationInfoOrNull(packageName)?.let { userIdOf(it.uid) }
        if (userId != thorUserId) return@withContext failure()
        try {
            val policyChanged = DhizukuHelper.setRuntimePermission(
                context, packageName, permissionName, granted,
            )
            val succeeded = if (permissionName == GET_INSTALLED_APPS_PERMISSION) {
                changeInstalledAppsAppOps(packageName, userId, granted, policyChanged)
            } else policyChanged
            if (succeeded) Result.success(Unit) else failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("DhizukuSystemGateway", "Permission change failed for $packageName/$permissionName", e)
            failure()
        }
    }

    /** OEM package visibility can be an app-op even when its runtime permission cannot be granted. */
    private fun changeInstalledAppsAppOps(
        packageName: String,
        userId: Int,
        granted: Boolean,
        policyChanged: Boolean,
    ): Boolean {
        val commands = if (granted) {
            installedAppsAppOpGrantCommands(packageName.escapeForShell(), userId)
        } else {
            installedAppsAppOpRevokeCommands(packageName.escapeForShell(), userId)
        }
        val outcomes = commands.map { command ->
            // These are our fixed appops-set builders: the operation immediately precedes the mode.
            val operation = command.substringBeforeLast(' ').substringAfterLast(' ')
            val before = DhizukuHelper.readAppOpMode(context, packageName, operation)
            val (code, output) = DhizukuHelper.execute(command)
            val after = DhizukuHelper.readAppOpMode(context, packageName, operation)
            if (code != 0) Logger.d("DhizukuSystemGateway", "App-op $operation failed: $output")
            AppOpChange(supported = before != null || after != null || code == 0, mode = after)
        }.filter { it.supported }
        if (granted) return policyChanged || outcomes.any { it.mode == AppOpsManager.MODE_ALLOWED }

        val permissionDenied = context.packageManager.checkPermission(
            GET_INSTALLED_APPS_PERMISSION, packageName,
        ) != PackageManager.PERMISSION_GRANTED
        // Reset every supported route. A read error or remaining UID-level allow cannot become a
        // successful revoke. MODE_DEFAULT hands control back to the now-denied runtime permission.
        return permissionDenied && if (outcomes.isEmpty()) policyChanged else outcomes.all {
            it.mode == AppOpsManager.MODE_DEFAULT || it.mode == AppOpsManager.MODE_IGNORED ||
                it.mode == AppOpsManager.MODE_ERRORED
        }
    }

    private data class AppOpChange(val supported: Boolean, val mode: Int?)

    private fun PrivilegeExecutionContext.ownerOperationTimeoutMillis(): Long =
        commandTimeout?.inWholeMilliseconds ?: OWNER_OPERATION_TIMEOUT_MS
}
