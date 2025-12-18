package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.repository.SystemRepositoryImpl

class ManageAppUseCase(
    private val systemRepository: com.valhalla.thor.domain.repository.SystemRepository
) {
    suspend fun forceStop(packageName: String): Result<Unit> =
        systemRepository.forceStopApp(packageName)

    suspend fun clearCache(packageName: String): Result<Unit> =
        systemRepository.clearCache(packageName)

    suspend fun setAppDisabled(packageName: String, disable: Boolean): Result<Unit> =
        systemRepository.setAppDisabled(packageName,disable)

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