// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import java.io.File

/**
 * Domain port for building a shareable/exportable app bundle in the given [BundleFormat].
 * Keeps the export/share use cases free of Android cache-dir / file-I/O concerns — the
 * concrete impl lives in the data layer. Signatures use only [File]/String/domain [AppInfo],
 * no Android types.
 */
interface AppBundleBuilder {
    /**
     * @param cacheSubDir staging scope under the cache dir. Distinct scopes never touch each
     *   other's files, which is what lets a batch stage a package the user is *also* exporting on
     *   its own at that moment — the builder wipes its per-package directory on entry, so two runs
     *   sharing one scope can delete each other's work mid-copy.
     * @param format defaults to [BundleFormat.autoFor], i.e. `.apk` for an app with no splits
     *   and `.apks` otherwise — never `.xapk`, which the caller must ask for.
     * @param fileName the name to give the bundle, or null to let the builder derive one from the
     *   app's label and version. A batch passes one because two packages can share a label, and
     *   the destination is written by name.
     */
    suspend fun build(
        appInfo: AppInfo,
        cacheSubDir: String = "share_temp",
        format: BundleFormat = BundleFormat.autoFor(appInfo),
        fileName: String? = null,
    ): Result<File>
}
