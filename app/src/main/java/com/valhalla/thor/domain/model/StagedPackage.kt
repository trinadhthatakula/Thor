// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.io.File

/**
 * The installer input after it has been copied into app-private storage — the ONE read of the
 * caller's `content://` URI.
 *
 * `PortableInstallerActivity` is exported and takes an ACTION_VIEW URI from any app on the
 * device, and in a privileged install mode there is no OS confirmation dialog. Reading that URI
 * twice — once to build the sheet the user approves, once to install — lets the provider serve
 * two different files: a clean APK for the "4 permissions" the sheet shows, spyware for the
 * privileged install that follows. So the bytes the user was shown are the bytes that get
 * installed, and the URI is never opened again. Whether that install also *grants* those
 * permissions is now the user's `grantAllPermissionsOnInstall` answer (GH#445) and does not change
 * the argument here: the sheet is a claim about what the substituted APK would be free to ask for.
 *
 * @param file app-private copy of the input. It OUTLIVES the analysis that produced it — whoever
 *   asked for the analysis owns it and must delete it on every exit path (installed, failed,
 *   dismissed).
 * @param displayName the provider's display name, captured at analysis time. Carried rather than
 *   re-queried so the bundle-vs-monolithic decision is made about the same file twice.
 */
data class StagedPackage(
    val file: File,
    val displayName: String?,
    /** Pre-resolved APK entries for authenticated archive restores; null for ordinary installs. */
    val installSet: List<String>? = null,
)

/**
 * What an analysis produced: the metadata the sheet renders, and the staged bytes it was read
 * from. Kept together so a caller cannot show one and install the other.
 */
data class AnalyzedPackage(
    val metadata: AppMetadata,
    val staged: StagedPackage
)
