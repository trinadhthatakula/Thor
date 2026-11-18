// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

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
    val appName: String? = null,
    val packageName: String?,
    val sizeBytes: Long,
    val dateModifiedEpochSec: Long,
    val kind: BackupArchiveKind,
    val extension: String,
)

enum class BackupArchiveSourceKind { DOWNLOADS, CUSTOM_FOLDER }

/** A failed location can coexist with successfully discovered archives. */
data class UnavailableArchiveSource(
    val kind: BackupArchiveSourceKind,
    val uriString: String? = null,
)

data class BackupArchiveScan(
    val archives: List<BackupArchiveItem>,
    val unavailableSources: List<UnavailableArchiveSource> = emptyList(),
)

enum class ArchiveDeleteFailure { ACCESS_DENIED, READ_ONLY, NOT_FOUND, UNAVAILABLE }

sealed interface ArchiveDeleteResult {
    data object Deleted : ArchiveDeleteResult
    data class Failed(val reason: ArchiveDeleteFailure) : ArchiveDeleteResult
}

/** Scanner discovering archives stored in Downloads/Thor or custom SAF folders. */
interface BackupArchiveScanner {
    fun scanBackups(): Flow<BackupArchiveScan>
    suspend fun deleteArchive(item: BackupArchiveItem): ArchiveDeleteResult
}
