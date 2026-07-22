// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.AppRepository
import org.koin.core.annotation.Factory

@Factory
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