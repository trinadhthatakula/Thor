// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

interface LegacyFixStoreInstaller {
    val sdkInt: Int

    suspend fun reinstall(
        packageName: String,
        requestInstall: suspend (uri: String) -> Result<Boolean>,
    ): Result<Unit>
}

class LegacyFixStoreCancelled : Exception()
