// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ComponentOverrideDao {

    /**
     * Every row for one package and user, as a stream.
     *
     * A `Flow` rather than a one-shot read because the Components tab shows the "N restricted by
     * Thor" header beside a list the user is actively changing; re-querying by hand after each
     * toggle is how that count drifts one behind.
     */
    @Query("SELECT * FROM component_overrides WHERE packageName = :packageName AND userId = :userId")
    fun observeForPackage(packageName: String, userId: Int): Flow<List<ComponentOverrideEntity>>

    /** Every row, for the cross-app "restore everything Thor changed" path. */
    @Query("SELECT * FROM component_overrides ORDER BY packageName, className")
    suspend fun getAll(): List<ComponentOverrideEntity>

    /**
     * `REPLACE`, so re-disabling a component Thor had already disabled refreshes the timestamp
     * rather than failing the insert. `restoreToEnabled` is rewritten with it, which is correct:
     * the manifest default is re-read from the app that is installed *now*, and an update may have
     * changed it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ComponentOverrideEntity)

    @Query(
        "DELETE FROM component_overrides " +
                "WHERE packageName = :packageName AND className = :className AND userId = :userId"
    )
    suspend fun delete(packageName: String, className: String, userId: Int)

    @Query("DELETE FROM component_overrides WHERE packageName = :packageName AND userId = :userId")
    suspend fun deleteForPackage(packageName: String, userId: Int)
}
