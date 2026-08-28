// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationBusy
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.model.restorePlanFor
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.domain.repository.SystemRepository
import kotlin.time.Duration
import org.koin.core.annotation.Factory

@Factory
class ManageAppUseCase(
    private val systemRepository: SystemRepository,
    private val packageOperationCoordinator: PackageOperationCoordinator,
) {
    suspend fun forceStop(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, PackageOperationOwner.FORCE_STOP, execution) {
        systemRepository.forceStopApp(packageName, execution)
    }

    /** Root only, and the `Long?` is bytes freed — see [SystemRepository.clearCache]. */
    suspend fun clearCache(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Long?> = withPackageLease(packageName, PackageOperationOwner.CLEAR_CACHE, execution) {
        systemRepository.clearCache(packageName, execution)
    }

    /** Every app's cache, under any mode that can. The `Long?` is bytes freed. */
    suspend fun clearAllCaches(): Result<Long?> = systemRepository.clearAllCaches()

    suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, PackageOperationOwner.CLEAR_DATA, execution) {
        systemRepository.clearAppData(packageName, execution)
    }

    /**
     * The raw primitive, with **no [com.valhalla.thor.domain.model.FreezeTier] check**. To freeze
     * a single app use [FreezeAppUseCase]; it resolves the tier and refuses a blocked one.
     *
     * Left ungated so the batch paths — `BulkFreezeRunner`, `MainViewModel.performCountedFreeze`,
     * `AppListViewModel.performMultiAction` — keep freezing from a target list they already
     * filtered against one shared snapshot, instead of paying a per-package re-read and telling
     * the user twice about apps they were already told were skipped. `disabled = false` is never
     * gated anywhere: unfreezing is the way out of a bad freeze.
     */
    suspend fun setAppDisabled(
        packageName: String,
        disabled: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, freezeOwner(disabled), execution) {
        setAppDisabledUncoordinated(packageName, disabled, execution)
    }

    /** Ungated for the same reasons as [setAppDisabled]; see [FreezeAppUseCase]. */
    suspend fun setAppSuspended(
        packageName: String,
        suspended: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, freezeOwner(suspended), execution) {
        setAppSuspendedUncoordinated(packageName, suspended, execution)
    }

    /**
     * Bring an app fully back to active: unsuspend if suspended AND enable if disabled
     * (both, when both apply). Safely handles the mixed disabled+suspended state. GH#239.
     */
    suspend fun restoreApp(
        packageName: String,
        enabled: Boolean,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, PackageOperationOwner.UNFREEZE, execution) {
        val plan = restorePlanFor(enabled, isSuspended)
        if (plan.unsuspend) {
            val unsuspend = setAppSuspendedUncoordinated(packageName, false, execution)
            if (unsuspend.isFailure) return@withPackageLease unsuspend
        }
        if (plan.enable) setAppDisabledUncoordinated(
            packageName,
            false,
            execution
        ) else Result.success(Unit)
    }

    /**
     * Force an app fully back to active for bulk "unfreeze all" when per-app state isn't known:
     * unsuspend then enable, unconditionally. Unsuspending a non-suspended app and enabling an
     * already-enabled app are no-ops, so this restores disabled AND suspended apps alike without a
     * prior state query. GH#239.
     */
    suspend fun forceUnfreeze(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, PackageOperationOwner.UNFREEZE, execution) {
        val unsuspend = setAppSuspendedUncoordinated(packageName, false, execution)
        if (unsuspend.isFailure) return@withPackageLease unsuspend
        setAppDisabledUncoordinated(packageName, false, execution)
    }

    suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, PackageOperationOwner.UNINSTALL, execution) {
        systemRepository.uninstallApp(packageName, execution)
    }

    /**
     * Reinstalls an app (usually via Play Store mechanism or existing APK).
     * For clean arch, we might need a specific method in Repo for "Reinstall".
     * Assuming for now we rely on the repository's generic install or a specific reinstall method.
     */
    suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, PackageOperationOwner.REINSTALL, execution) {
        systemRepository.reinstallAppWithGoogle(packageName, execution)
    }

    private suspend fun setAppDisabledUncoordinated(
        packageName: String,
        disabled: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = systemRepository.setAppDisabled(packageName, disabled, execution)

    private suspend fun setAppSuspendedUncoordinated(
        packageName: String,
        suspended: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = systemRepository.setAppSuspended(packageName, suspended, execution)

    private suspend fun <T> withPackageLease(
        packageName: String,
        owner: PackageOperationOwner,
        execution: PrivilegeExecutionContext,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var operationResult: Result<T>? = null
        return when (
            val lease = packageOperationCoordinator.withPackageLease(
                packageName = packageName,
                owner = owner,
                admissionTimeout = execution.lane.admissionTimeout(),
            ) {
                block().also { operationResult = it }
            }
        ) {
            is PackageLeaseResult.Acquired<*> -> checkNotNull(operationResult)
            is PackageLeaseResult.Busy -> Result.failure(PackageOperationBusy(lease.owner))
        }
    }

    private fun freezeOwner(frozen: Boolean): PackageOperationOwner =
        if (frozen) PackageOperationOwner.FREEZE else PackageOperationOwner.UNFREEZE

    private fun PrivilegeExecutionLane.admissionTimeout(): Duration = when (this) {
        PrivilegeExecutionLane.INTERACTIVE -> PrivilegeExecutionTimeouts.INTERACTIVE_ADMISSION
        PrivilegeExecutionLane.ARCHIVE -> PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION
        PrivilegeExecutionLane.SWEEP -> PrivilegeExecutionTimeouts.SWEEP_ADMISSION
    }
}
