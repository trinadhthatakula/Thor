// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.restorePlanFor
import org.koin.core.annotation.Factory

@Factory
class ManageAppUseCase(
    private val systemRepository: com.valhalla.thor.domain.repository.SystemRepository
) {
    suspend fun forceStop(packageName: String): Result<Unit> =
        systemRepository.forceStopApp(packageName)

    /** Root only, and the `Long?` is bytes freed — see `SystemRepository.clearCache`. */
    suspend fun clearCache(packageName: String): Result<Long?> =
        systemRepository.clearCache(packageName)

    /** Every app's cache, under any mode that can. The `Long?` is bytes freed. */
    suspend fun clearAllCaches(): Result<Long?> =
        systemRepository.clearAllCaches()

    suspend fun clearAppData(packageName: String): Result<Unit> =
        systemRepository.clearAppData(packageName)

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
    suspend fun setAppDisabled(packageName: String, disabled: Boolean): Result<Unit> =
        systemRepository.setAppDisabled(packageName, disabled)

    /** Ungated for the same reasons as [setAppDisabled]; see [FreezeAppUseCase]. */
    suspend fun setAppSuspended(packageName: String, suspended: Boolean): Result<Unit> =
        systemRepository.setAppSuspended(packageName, suspended)

    /**
     * Bring an app fully back to active: unsuspend if suspended AND enable if disabled
     * (both, when both apply). Safely handles the mixed disabled+suspended state. GH#239.
     */
    suspend fun restoreApp(packageName: String, enabled: Boolean, isSuspended: Boolean): Result<Unit> {
        val plan = restorePlanFor(enabled, isSuspended)
        if (plan.unsuspend) {
            val r = setAppSuspended(packageName, false)
            if (r.isFailure) return r
        }
        return if (plan.enable) setAppDisabled(packageName, false) else Result.success(Unit)
    }

    /**
     * Force an app fully back to active for bulk "unfreeze all" when per-app state isn't known:
     * unsuspend then enable, unconditionally. Unsuspending a non-suspended app and enabling an
     * already-enabled app are no-ops, so this restores disabled AND suspended apps alike without a
     * prior state query. GH#239.
     */
    suspend fun forceUnfreeze(packageName: String): Result<Unit> {
        val unsuspend = setAppSuspended(packageName, false)
        if (unsuspend.isFailure) return unsuspend
        return setAppDisabled(packageName, false)
    }

    suspend fun uninstallApp(packageName: String): Result<Unit> =
        systemRepository.uninstallApp(packageName)

    /**
     * Reinstalls an app (usually via Play Store mechanism or existing APK).
     * For clean arch, we might need a specific method in Repo for "Reinstall".
     * Assuming for now we rely on the repository's generic install or a specific reinstall method.
     */
    suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        return systemRepository.reinstallAppWithGoogle(packageName)
    }

}