package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.model.AppInfo

class GetAppDetailsUseCase(
    private val appRepository: AppRepository
) {
    suspend operator fun invoke(packageName: String): Result<AppInfo> {
        return try {
            val info = appRepository.getAppDetails(packageName)
            if (info != null) {
                Result.success(info)
            } else {
                Result.failure(Exception("App not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}