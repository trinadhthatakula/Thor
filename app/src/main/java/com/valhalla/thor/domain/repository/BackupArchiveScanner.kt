// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/** Classification of backup files discovered in storage. */
enum class BackupArchiveKind {
    /** Encrypted full app data archive (.thorbak). */
    DATA_BACKUP,

    /** Standalone installer or bundle (.xapk, .apks, .apk). */
    APP_BUNDLE,
}

/** Represents a single discovered backup or export archive. */
data class BackupArchiveItem(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val packageName: String?,
    val sizeBytes: Long,
    val dateModifiedEpochSec: Long,
    val kind: BackupArchiveKind,
    val extension: String,
)

/** Scanner discovering archives stored in Downloads/Thor or custom SAF folders. */
interface BackupArchiveScanner {
    fun scanBackups(): Flow<List<BackupArchiveItem>>
    suspend fun deleteArchive(item: BackupArchiveItem): Boolean
}
