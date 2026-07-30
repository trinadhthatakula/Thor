// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.FreezeProfile
import kotlinx.coroutines.flow.Flow

interface FreezeProfileRepository {
    fun observeProfiles(): Flow<List<FreezeProfile>>

    /** The packages in one profile, or an empty list if it no longer exists. */
    suspend fun packagesOf(profileId: Long): List<String>

    /**
     * Every package that belongs to at least one profile.
     *
     * Exists for `FreezerBridgeProvider`: an app can be frozen by a profile without ever being
     * on the watchlist, and the restore gate has to let the launcher wake it up again.
     */
    suspend fun allProfilePackageNames(): Set<String>

    /** @return the new profile's id. */
    suspend fun create(name: String, packageNames: List<String>): Long

    /**
     * Apply an editor save — name and membership together.
     *
     * One call rather than a rename plus a set-apps because they are one user action and the
     * rename is the half that can fail on the unique-name index; splitting them would let a
     * rejected rename ship the membership change anyway.
     */
    suspend fun update(profileId: Long, name: String, packageNames: List<String>)

    suspend fun delete(profileId: Long)
}
