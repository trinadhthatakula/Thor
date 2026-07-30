// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FreezeProfileDao {

    @Transaction
    @Query("SELECT * FROM freeze_profiles ORDER BY name COLLATE NOCASE ASC")
    fun observeProfiles(): Flow<List<FreezeProfileWithApps>>

    @Query("SELECT packageName FROM freeze_profile_apps WHERE profileId = :profileId")
    suspend fun packagesOf(profileId: Long): List<String>

    /** Every package that belongs to at least one profile — the restore gate's second source. */
    @Query("SELECT DISTINCT packageName FROM freeze_profile_apps")
    suspend fun allProfilePackages(): List<String>

    @Insert
    suspend fun insertProfile(entity: FreezeProfileEntity): Long

    @Query("UPDATE freeze_profiles SET name = :name WHERE id = :profileId")
    suspend fun renameProfile(profileId: Long, name: String)

    @Query("DELETE FROM freeze_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: Long)

    @Query("DELETE FROM freeze_profile_apps WHERE profileId = :profileId")
    suspend fun clearApps(profileId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertApps(entities: List<FreezeProfileAppEntity>)

    /**
     * Create a profile and its membership as one unit.
     *
     * Transactional because the half-written alternative is a *named, empty* profile: the sheet
     * lists it, freezing it does nothing, and the user has no way to tell that from a profile
     * whose apps were all uninstalled.
     */
    @Transaction
    suspend fun createProfile(entity: FreezeProfileEntity, packageNames: List<String>): Long {
        val id = insertProfile(entity)
        insertApps(packageNames.map { FreezeProfileAppEntity(id, it) })
        return id
    }

    /**
     * Replace a profile's membership wholesale.
     *
     * Delete-then-insert rather than a diff: the editor hands back the full set the user ticked,
     * and a diff would have to be computed from a read that is already stale by the time it runs.
     */
    @Transaction
    suspend fun replaceApps(profileId: Long, packageNames: List<String>) {
        clearApps(profileId)
        insertApps(packageNames.map { FreezeProfileAppEntity(profileId, it) })
    }

    /**
     * Apply one editor save — the new name and the new membership — as a single unit.
     *
     * Two calls would not do. `name` carries a unique index, so the rename is the half that can
     * fail; running it second would leave the membership already replaced under the old name, and
     * running it first still splits one user action into two outcomes the UI would have to
     * reconcile. Inside a transaction the constraint violation rolls the membership back with it.
     */
    @Transaction
    suspend fun updateProfile(profileId: Long, name: String, packageNames: List<String>) {
        renameProfile(profileId, name)
        replaceApps(profileId, packageNames)
    }
}
