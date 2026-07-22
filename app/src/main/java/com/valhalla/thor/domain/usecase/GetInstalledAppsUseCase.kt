// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.AppRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory

@Factory
class GetInstalledAppsUseCase(
    private val appRepository: AppRepository
) {
    /**
     * Returns a generic Pair: (UserApps, SystemApps)
     */
    operator fun invoke(): Flow<Pair<List<AppInfo>, List<AppInfo>>> {
        return appRepository.getAllApps().map { allApps ->
            val (system, user) = allApps.partition { it.isSystem }
            // Additional filtering can go here (e.g. exclude own app?)
            Pair(user, system)
        }
    }
}