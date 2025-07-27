package com.valhalla.thor.model.backup

import kotlinx.serialization.Serializable


/**
 * Enum representing different parts of an application that can be backed up.
 */
@Serializable // Make enum serializable for AppBackupInfo
enum class BackupPart {
    APK,          // The application's installation file(s)
    DATA,         // Internal app data (e.g., databases, shared preferences)
    EXT_DATA,     // External app-specific data (e.g., Android/data/<package_name>)
    OBB,          // Opaque Binary Blob files (expansion files for games/large apps)
    MEDIA         // Associated media files (user-generated, app-specific)
}