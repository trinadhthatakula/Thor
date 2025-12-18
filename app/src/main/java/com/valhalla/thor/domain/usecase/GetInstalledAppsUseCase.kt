package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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