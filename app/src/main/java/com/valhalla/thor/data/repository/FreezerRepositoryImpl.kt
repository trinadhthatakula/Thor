// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.room.FreezerDao
import com.valhalla.thor.data.source.local.room.FreezerEntity
import com.valhalla.thor.domain.repository.FreezerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [FreezerRepository::class])
class FreezerRepositoryImpl(
    private val freezerDao: FreezerDao
) : FreezerRepository {

    override fun getAll(): Flow<List<String>> =
        freezerDao.getAll().map { list -> list.map { it.packageName } }

    override suspend fun getAllPackageNames(): List<String> =
        freezerDao.getAllPackageNames()

    override suspend fun add(packageName: String) {
        freezerDao.insert(FreezerEntity(packageName))
    }

    override suspend fun remove(packageName: String) {
        freezerDao.delete(packageName)
    }

    override suspend fun contains(packageName: String): Boolean =
        freezerDao.contains(packageName)
}
