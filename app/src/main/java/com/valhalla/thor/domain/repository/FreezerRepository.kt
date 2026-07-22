// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import kotlinx.coroutines.flow.Flow

interface FreezerRepository {
    fun getAll(): Flow<List<String>>
    suspend fun getAllPackageNames(): List<String>
    suspend fun add(packageName: String)
    suspend fun remove(packageName: String)
    suspend fun contains(packageName: String): Boolean
}
