// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.room.ComponentOverrideDao
import com.valhalla.thor.data.source.local.room.ComponentOverrideEntity
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.ComponentOverride
import com.valhalla.thor.domain.model.ComponentType
import com.valhalla.thor.domain.repository.ComponentOverrideRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [ComponentOverrideRepository::class])
class ComponentOverrideRepositoryImpl(
    private val dao: ComponentOverrideDao,
) : ComponentOverrideRepository {

    override fun observe(packageName: String): Flow<List<ComponentOverride>> =
        dao.observeForPackage(packageName, thorUserId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<ComponentOverride> =
        // Filtered here rather than in SQL so the DAO keeps one "everything" query: the table is
        // small (it holds only what Thor changed) and this runs once, behind a confirmation dialog.
        dao.getAll().filter { it.userId == thorUserId }.map { it.toDomain() }

    override suspend fun record(
        packageName: String,
        className: String,
        type: ComponentType,
        restoreToEnabled: Boolean,
    ) {
        dao.upsert(
            ComponentOverrideEntity(
                packageName = packageName,
                className = className,
                userId = thorUserId,
                componentType = type.name,
                restoreToEnabled = restoreToEnabled,
                disabledAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun forget(packageName: String, className: String) {
        dao.delete(packageName, className, thorUserId)
    }

    override suspend fun forgetPackage(packageName: String) {
        dao.deleteForPackage(packageName, thorUserId)
    }
}

/**
 * An unrecognised `componentType` degrades to [ComponentType.ACTIVITY] rather than dropping the row.
 *
 * The column holds an enum *name*, so the only way it can fail to parse is a downgrade to a Thor
 * that no longer knows a type a later version wrote. Losing the row would lose the record of a
 * component Thor disabled — leaving it disabled with nothing to restore it from, which is the one
 * outcome this table exists to prevent. Being filed under the wrong heading costs a misplaced row.
 */
private fun ComponentOverrideEntity.toDomain(): ComponentOverride = ComponentOverride(
    packageName = packageName,
    className = className,
    type = ComponentType.entries.firstOrNull { it.name == componentType } ?: ComponentType.ACTIVITY,
    restoreToEnabled = restoreToEnabled,
    disabledAt = disabledAt,
)
