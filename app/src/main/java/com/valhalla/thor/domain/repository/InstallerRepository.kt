// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import android.net.Uri

enum class InstallMode {
    NORMAL,
    SHIZUKU,
    DHIZUKU,
    ROOT,
    EXTERNAL
}

/**
 * The Repository Contract.
 * The Domain layer doesn't care about PackageInstaller APIs, only that we can install a URI.
 */
interface InstallerRepository {
    suspend fun installPackage(uri: Uri, mode: InstallMode, canDowngrade: Boolean = false)
}
