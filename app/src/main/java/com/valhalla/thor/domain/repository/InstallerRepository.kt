// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import android.net.Uri
import com.valhalla.thor.domain.model.StagedPackage

enum class InstallMode {
    NORMAL,
    SHIZUKU,
    DHIZUKU,
    ROOT,
    EXTERNAL
}

/**
 * The Repository Contract.
 * The Domain layer doesn't care about PackageInstaller APIs, only that we can install a package.
 */
interface InstallerRepository {
    /**
     * Install the already-staged [staged] bytes — the ones the user was shown. The URI is never
     * re-opened here; see [StagedPackage] for why.
     *
     * @param uri the original input, needed ONLY by [InstallMode.EXTERNAL], which hands the job
     *   to another installer app rather than installing anything itself (that app does its own
     *   read and shows its own confirmation, so the read-once rule is not ours to enforce there —
     *   and a private staging path is not something another app could open anyway).
     */
    suspend fun installPackage(
        staged: StagedPackage,
        uri: Uri,
        mode: InstallMode,
        canDowngrade: Boolean = false
    )
}
