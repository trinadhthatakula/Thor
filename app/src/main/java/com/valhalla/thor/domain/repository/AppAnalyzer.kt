// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import android.net.Uri
import com.valhalla.thor.domain.model.AnalyzedPackage

interface AppAnalyzer {
    /**
     * Copies a URI (APK, XAPK, APKS) into app-private storage exactly once and extracts metadata
     * from that copy, without installing it.
     *
     * The staged file outlives this call so the install can use the very bytes the metadata
     * describes — see [com.valhalla.thor.domain.model.StagedPackage]. The caller owns it and must
     * pass it to [discard] on every exit path.
     */
    suspend fun analyze(uri: Uri): Result<AnalyzedPackage>

    /**
     * Delete the file staged by [analyze]. Idempotent, and a no-op for null, so it is safe to
     * call from a teardown path that may or may not have staged anything.
     */
    fun discard(analyzed: AnalyzedPackage?)
}
