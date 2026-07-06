package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.restorePlanFor
import org.koin.core.annotation.Factory

@Factory
class ManageAppUseCase(
    private val systemRepository: com.valhalla.thor.domain.repository.SystemRepository
) {
    suspend fun forceStop(packageName: String): Result<Unit> =
        systemRepository.forceStopApp(packageName)

    suspend fun clearCache(packageName: String): Result<Unit> =
        systemRepository.clearCache(packageName)

    suspend fun clearAppData(packageName: String): Result<Unit> =
        systemRepository.clearAppData(packageName)

    suspend fun setAppDisabled(packageName: String, disabled: Boolean): Result<Unit> =
        systemRepository.setAppDisabled(packageName, disabled)

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