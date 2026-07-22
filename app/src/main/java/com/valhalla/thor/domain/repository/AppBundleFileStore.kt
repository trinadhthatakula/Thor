// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import java.io.File

/**
 * Domain port for persisting/sharing an already-built app bundle. Keeps the
 * export/share use cases free of Android file-I/O concerns (MediaStore, SAF,
 * FileProvider, ContentResolver, Environment) — the concrete impl lives in the
 * data layer. Signatures use only [File]/String/primitives, no Android types.
 */
interface AppBundleFileStore {
    /** Write [file] to public Downloads/Thor; returns a human-readable location label. */
    suspend fun writeToDownloads(file: File, mime: String): String

    /** Write [file] into the user-picked SAF tree [treeUriStr]; returns a location label. */
    suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String

    /** True when [treeUriStr] resolves to a currently writable SAF tree. */
    suspend fun isTreeWritable(treeUriStr: String?): Boolean

    /** Label to show for the current export target given the saved SAF tree URI. */
    suspend fun currentTargetLabel(savedTreeUriStr: String?): String

    /** Content-uri (as String) to share [file] via FileProvider. */
    fun shareUri(file: File): String
}
