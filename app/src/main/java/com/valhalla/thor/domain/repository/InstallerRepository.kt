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
     * @param grantAllPermissions the answer for THIS install to "grant every runtime permission
     *   the package declares, without asking" — `pm install-create -g`, the GH#445 flag. `null`,
     *   the default, means "no answer for this install, use the saved setting"; that is what every
     *   caller with no user in front of it wants, and it is why this is nullable rather than
     *   defaulting to `false` — a `false` default would silently override a user who had turned the
     *   setting on. The portable installer passes a concrete value because it shows the user a
     *   checkbox, seeded from the setting, that they may flip for one install without the setting
     *   changing underneath them. Reaches only the shell rungs; see `installViaSessionCommand`.
     */
    suspend fun installPackage(
        staged: StagedPackage,
        uri: Uri,
        mode: InstallMode,
        canDowngrade: Boolean = false,
        grantAllPermissions: Boolean? = null,
    )
}
