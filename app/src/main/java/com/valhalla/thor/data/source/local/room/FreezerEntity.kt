// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "freezer_apps")
data class FreezerEntity(
    @PrimaryKey val packageName: String
)
