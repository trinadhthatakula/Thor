// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import kotlinx.coroutines.flow.Flow

interface FreezerRepository {
    fun getAll(): Flow<List<String>>
    suspend fun getAllPackageNames(): List<String>
    suspend fun add(packageName: String)
    suspend fun remove(packageName: String)

    /**
     * Drop several rows at once, with no restore attempted for any of them.
     *
     * The single-package [remove] is only ever reached through a gesture that unfreezes first
     * ([com.valhalla.thor.presentation.freezer.FreezerViewModel.removeFromFreezer] and its two
     * siblings), and that ordering is a deliberate invariant — GH#310. This one deliberately has no
     * restore step because its only caller is the scan-driven prune, where the package is gone: an
     * unfreeze would be a privileged call against a package that no longer exists, and the row it
     * is removing describes nothing that can be left frozen.
     */
    suspend fun removeAll(packageNames: Set<String>)

    suspend fun contains(packageName: String): Boolean
}
