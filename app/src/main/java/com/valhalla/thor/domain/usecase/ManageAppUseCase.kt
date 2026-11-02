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
import com.valhalla.thor.domain.model.SuspensionChange
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.domain.repository.PackageOperationBarrier
import com.valhalla.thor.domain.repository.SystemRepository
import kotlin.time.Duration
import org.koin.core.annotation.Factory

@Factory
class ManageAppUseCase(
    private val systemRepository: SystemRepository,
    private val packageOperationCoordinator: PackageOperationCoordinator,
    private val packageOperationBarrier: PackageOperationBarrier,
) {
    /** Existing isolated callers can supply only their own coordinator; production injects all three. */
    constructor(
        systemRepository: SystemRepository,
        packageOperationCoordinator: PackageOperationCoordinator,
    ) : this(systemRepository, packageOperationCoordinator, PackageOperationBarrier { _, _ -> false })

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
        clearCacheUncoordinated(packageName, execution)
    }

    internal suspend fun clearCacheUncoordinated(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Long?> = systemRepository.clearCache(packageName, execution)

    /** Every app's cache, under any mode that can. The `Long?` is bytes freed. */
    suspend fun clearAllCaches(
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Long?> = when (
        val lease = packageOperationBarrier.withGlobalLease { systemRepository.clearAllCaches(execution) }
    ) {
        is PackageLeaseResult.Acquired -> lease.value
        is PackageLeaseResult.Busy -> Result.failure(PackageOperationBusy(lease.owner))
    }

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
     * Left ungated so the batch paths — `the privilege sweep worker`, `MainViewModel.performCountedFreeze`,
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

    /** Read-only check for recovery surfaces that must distinguish remaining external pauses. */
    suspend fun isAppSuspendedByThor(packageName: String): Boolean? =
        systemRepository.isAppSuspendedByThor(packageName)

    /** Ungated for the same reasons as [setAppDisabled]; see [FreezeAppUseCase]. */
    suspend fun setAppSuspended(
        packageName: String,
        suspended: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<SuspensionChange> = withPackageLease(packageName, freezeOwner(suspended), execution) {
        setAppSuspendedUncoordinated(packageName, suspended, execution)
    }

    /** Convert modes without an active window, and without lifting somebody else's suspension. */
    suspend fun freezeForFreezer(
        packageName: String,
        mode: FreezerMode,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> = withPackageLease(packageName, PackageOperationOwner.FREEZE, execution) {
        freezeForFreezerUncoordinated(packageName, mode, execution)
    }

    internal suspend fun freezeForFreezerUncoordinated(
        packageName: String,
        mode: FreezerMode,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = when (mode) {
        FreezerMode.FREEZE -> {
            val disabled = setAppDisabledUncoordinated(packageName, true, execution)
            if (disabled.isFailure) disabled
            else {
                // Disabling is the requested restriction. If ownership cannot be verified, keep
                // the suspension and still track the successful freeze; never strand a disabled app
                // off the watchlist merely because optional mode-conversion cleanup was refused.
                val cleanup = setAppSuspendedUncoordinated(packageName, false, execution)
                val failure = cleanup.exceptionOrNull()
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Result.success(Unit)
            }
        }
        FreezerMode.SUSPEND -> {
            val suspended = setAppSuspendedUncoordinated(packageName, true, execution)
            if (suspended.isFailure) Result.failure(requireNotNull(suspended.exceptionOrNull()))
            else setAppDisabledUncoordinated(packageName, false, execution)
        }
    }

    /** Clear disabled/hidden state first, even when ownership readback later refuses unsuspension. */
    suspend fun restoreApp(
        packageName: String,
        enabled: Boolean,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<SuspensionChange> = withPackageLease(packageName, PackageOperationOwner.UNFREEZE, execution) {
        val plan = restorePlanFor(enabled, isSuspended)
        if (plan.enable) {
            val enabledResult = setAppDisabledUncoordinated(packageName, false, execution)
            if (enabledResult.isFailure) return@withPackageLease Result.failure(requireNotNull(enabledResult.exceptionOrNull()))
        }
        if (plan.unsuspend) setAppSuspendedUncoordinated(packageName, false, execution)
        else Result.success(SuspensionChange())
    }

    suspend fun forceUnfreeze(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<SuspensionChange> = withPackageLease(packageName, PackageOperationOwner.UNFREEZE, execution) {
        forceUnfreezeUncoordinated(packageName, execution)
    }

    internal suspend fun forceUnfreezeUncoordinated(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<SuspensionChange> {
        val enabled = setAppDisabledUncoordinated(packageName, false, execution)
        if (enabled.isFailure) return Result.failure(requireNotNull(enabled.exceptionOrNull()))
        return setAppSuspendedUncoordinated(packageName, false, execution)
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
        reinstallAppWithGoogleUncoordinated(packageName, execution)
    }

    internal suspend fun reinstallAppWithGoogleUncoordinated(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = systemRepository.reinstallAppWithGoogle(packageName, execution)

    internal suspend fun setAppDisabledUncoordinated(
        packageName: String,
        disabled: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = systemRepository.setAppDisabled(packageName, disabled, execution)

    internal suspend fun setAppSuspendedUncoordinated(
        packageName: String,
        suspended: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<SuspensionChange> = systemRepository.setAppSuspended(packageName, suspended, execution)

    internal suspend fun <T> withPackageOperation(
        packageName: String,
        owner: PackageOperationOwner,
        execution: PrivilegeExecutionContext,
        block: suspend () -> T,
    ): PackageLeaseResult<T> = packageOperationCoordinator.withPackageLease(
        packageName = packageName,
        owner = owner,
        admissionTimeout = execution.lane.admissionTimeout(),
        block = block,
    )

    private suspend fun <T> withPackageLease(
        packageName: String,
        owner: PackageOperationOwner,
        execution: PrivilegeExecutionContext,
        block: suspend () -> Result<T>,
    ): Result<T> = when (
        val lease = withPackageOperation(packageName, owner, execution, block)
    ) {
        is PackageLeaseResult.Acquired -> lease.value
        is PackageLeaseResult.Busy -> Result.failure(PackageOperationBusy(lease.owner))
    }

    private fun freezeOwner(frozen: Boolean): PackageOperationOwner =
        if (frozen) PackageOperationOwner.FREEZE else PackageOperationOwner.UNFREEZE

    private fun PrivilegeExecutionLane.admissionTimeout(): Duration = when (this) {
        PrivilegeExecutionLane.INTERACTIVE -> PrivilegeExecutionTimeouts.INTERACTIVE_ADMISSION
        PrivilegeExecutionLane.ARCHIVE -> PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION
        PrivilegeExecutionLane.SWEEP -> PrivilegeExecutionTimeouts.SWEEP_ADMISSION
    }
}
