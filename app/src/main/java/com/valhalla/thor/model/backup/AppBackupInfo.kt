package com.valhalla.thor.model.backup

/**
 * Data class representing the metadata of a single app backup.
 * This information will be stored alongside the backup files to facilitate restoration.
 */
data class AppBackupInfo(
    val packageName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String,
    val backupTime: Long,          // Timestamp of when the backup was created
    val backupLocation: String,    // Absolute path to the root directory of this specific backup
    val backedUpParts: Set<BackupPart>, // Set of parts included in this backup
    val backupSize: Long = 0L,     // Total size of the backup in bytes
    val isSystemApp: Boolean = false, // Indicates if the app was a system app at backup time
    val isSplitApk: Boolean = false // Indicates if the app uses split APKs
)