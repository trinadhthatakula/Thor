// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.room.FreezeProfileDao
import com.valhalla.thor.data.source.local.room.FreezeProfileEntity
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeProfileIcon
import com.valhalla.thor.domain.model.ProfileAssignmentResult
import com.valhalla.thor.domain.model.normalizeProfileName
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [FreezeProfileRepository::class])
class FreezeProfileRepositoryImpl(
    private val freezeProfileDao: FreezeProfileDao,
) : FreezeProfileRepository {

    override fun observeProfiles(): Flow<List<FreezeProfile>> =
        freezeProfileDao.observeProfiles().map { rows ->
            rows.map { row ->
                FreezeProfile(
                    id = row.profile.id,
                    name = row.profile.name,
                    icon = FreezeProfileIcon.fromKey(row.profile.icon) ?: FreezeProfileIcon.FREEZER,
                    // Sorted here rather than in the query: the @Relation is a second SELECT
                    // whose ordering Room does not let us specify, so a stable order has to be
                    // imposed on this side or the editor's tick marks shuffle between reads.
                    packageNames = row.apps.map { it.packageName }.sorted(),
                )
            }
        }

    override suspend fun packagesOf(profileId: Long): List<String> =
        freezeProfileDao.packagesOf(profileId)

    override suspend fun allProfilePackageNames(): Set<String> =
        freezeProfileDao.allProfilePackages().toSet()

    // Normalised here, not only in the editor, so no caller can store a name that the
    // duplicate check would later fail to recognise as the same one.
    override suspend fun create(name: String, packageNames: List<String>, icon: FreezeProfileIcon): Long =
        freezeProfileDao.createProfile(
            FreezeProfileEntity(
                name = normalizeProfileName(name),
                icon = icon.key,
                createdAt = System.currentTimeMillis(),
            ),
            packageNames.distinct(),
        )

    override suspend fun update(profileId: Long, name: String, packageNames: List<String>, icon: FreezeProfileIcon?) =
        freezeProfileDao.updateProfile(
            profileId,
            normalizeProfileName(name),
            packageNames.distinct(),
            icon?.key,
        )

    override suspend fun changeIconIfCurrent(profileId: Long, expected: FreezeProfileIcon, icon: FreezeProfileIcon) =
        freezeProfileDao.changeIconIfCurrent(profileId, expected.key, icon.key) == 1

    override suspend fun delete(profileId: Long) =
        freezeProfileDao.deleteProfile(profileId)

    override suspend fun addApps(
        profileIds: Set<Long>,
        packageNames: Set<String>,
        addToFreezer: Boolean,
    ): ProfileAssignmentResult = freezeProfileDao.addApps(profileIds, packageNames, addToFreezer)
}
