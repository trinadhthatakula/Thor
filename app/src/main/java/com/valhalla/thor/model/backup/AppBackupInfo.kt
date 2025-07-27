package com.valhalla.thor.model.backup

import kotlinx.serialization.Serializable

/**
 * Data class representing the metadata of a single app backup.
 * This information will be stored alongside the backup files to facilitate restoration.
 *
 * @property backupLocation The absolute path to the root directory of this specific backup.
 * This will now be a directory, not a .tar.xz file by default.
 * @property archiveFilePath If the backup has been compressed, this will be the path to the .tar.xz file.
 * Null if the backup is uncompressed (directory-based).
 */
@Serializable // Make data class serializable for saving/loading metadata
data class AppBackupInfo(
    val packageName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String,
    val backupTime: Long,          // Timestamp of when the backup was created
    val backupLocation: String,    // Absolute path to the root directory of this specific backup (directory)
    val backedUpParts: Set<BackupPart>, // Set of parts included in this backup
    var backupSize: Long = 0L,     // Total size of the backup in bytes (can be updated after compression)
    val isSystemApp: Boolean = false, // Indicates if the app was a system app at backup time
    val isSplitApk: Boolean = false, // Indicates if the app uses split APKs
    val archiveFilePath: String? = null // Path to the .tar.xz archive if compressed
)