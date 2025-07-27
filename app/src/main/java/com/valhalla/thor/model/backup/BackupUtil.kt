package com.valhalla.thor.model.backup

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import com.valhalla.superuser.ShellUtils.fastCmd
import com.valhalla.thor.model.getRootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


/**
 * Object class providing utility functions for file paths and (future) zip/unzip operations.
 * This class encapsulates logic for determining standard Android app data paths.
 */
object BackupUtil {

    // Base paths for various app data locations.
    // These paths are standard on Android and typically require root for access to other apps' data.
    private const val DATA_APP_DIR = "/data/app"
    private const val DATA_DATA_DIR = "/data/data"
    private const val SDCARD_ANDROID_DATA_DIR = "/sdcard/Android/data"
    private const val SDCARD_ANDROID_OBB_DIR = "/sdcard/Android/obb"
    // Note: "/sdcard" is often a symlink to "/storage/emulated/0" on modern Android devices.
    private const val PUBLIC_BACKUP_BASE_DIR = "/sdcard/thor/backup" // Publicly accessible backup folder

    /**
     * Determines the path(s) to an application's APK file(s).
     * For split APKs, it returns a list of all APK paths.
     *
     * @param appInfo The ApplicationInfo object for the app.
     * @return A list of absolute paths to the app's APK files.
     */
    fun getAppApkPaths(appInfo: ApplicationInfo): List<String> {
        val apkPaths = mutableListOf<String>()
        // The base APK path
        appInfo.sourceDir?.let { apkPaths.add(it) }

        // Add paths for split APKs if they exist
        appInfo.splitSourceDirs?.forEach { splitApkPath ->
            splitApkPath?.let { apkPaths.add(it) }
        }
        return apkPaths
    }

    /**
     * Determines the path to an application's internal data directory.
     * This directory contains databases, shared preferences, and other private app files.
     * Requires root access to read/write for other apps.
     *
     * @param packageName The package name of the app.
     * @return The absolute path to the app's internal data directory.
     */
    fun getAppDataPath(packageName: String): String {
        return "$DATA_DATA_DIR/$packageName"
    }

    /**
     * Determines the path to an application's external app-specific data directory.
     * This is located on the shared external storage (e.g., /sdcard/Android/data/).
     *
     * @param packageName The package name of the app.
     * @return The absolute path to the app's external data directory.
     */
    fun getAppExternalDataPath(packageName: String): String {
        return "$SDCARD_ANDROID_DATA_DIR/$packageName"
    }

    /**
     * Determines the path to an application's OBB (Opaque Binary Blob) directory.
     * This contains expansion files, often used by games for large assets.
     * Located on the shared external storage (e.g., /sdcard/Android/obb/).
     *
     * @param packageName The package name of the app.
     * @return The absolute path to the app's OBB directory.
     */
    fun getAppObbPath(packageName: String): String {
        return "$SDCARD_ANDROID_OBB_DIR/$packageName"
    }

    /**
     * Determines common paths for app-associated media.
     * This is a more complex area as media can be stored in various user-accessible directories.
     * For a root-only solution, we can target common patterns.
     *
     * NOTE: This function provides *potential* paths. Identifying all app-specific media
     * without explicit app tagging or user input can be challenging.
     * You might need to refine this based on how specific apps store their media.
     *
     * @param packageName The package name of the app.
     * @return A list of potential absolute paths where the app might store media.
     */
    fun getAppMediaPaths(packageName: String): List<String> {
        val mediaPaths = mutableListOf<String>()
        // Common patterns for app-specific media within public directories:
        // 1. Subdirectory within Pictures/Videos/Music named after the app or package.
        //    Example: /sdcard/Pictures/MyAppName/
        // 2. Direct files in Downloads that might be associated.
        // 3. Custom directories used by specific apps (hard to generalize).

        // Example: Add a common pattern for app-specific media in Pictures
        mediaPaths.add("/sdcard/Pictures/${packageName.substringAfterLast('.')}") // Using last part of package name as folder name
        mediaPaths.add("/sdcard/Pictures/$packageName") // Full package name as folder name
        mediaPaths.add("/sdcard/DCIM/$packageName") // Some camera apps might use this
        mediaPaths.add("/sdcard/Music/$packageName")
        mediaPaths.add("/sdcard/Movies/$packageName")

        // You might need to add more paths here based on research for common apps
        // or provide a UI for the user to select specific media folders for an app.

        return mediaPaths
    }

    /**
     * Checks if an application is a system app.
     * System apps have specific flags in their ApplicationInfo.
     *
     * @param appInfo The ApplicationInfo object for the app.
     * @return True if the app is a system app, false otherwise.
     */
    fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    /**
     * Zips a given directory into a single .tar.xz archive file using root shell commands.
     * This method assumes 'tar' and 'xz' utilities are available via the root shell.
     *
     * @param sourcePath The absolute path to the directory to be zipped.
     * @param archiveFilePath The absolute path where the resulting .tar.xz file should be saved.
     * @return True if zipping was successful, false otherwise.
     */
    fun zipDirectory(
        sourcePath: String,
        archiveFilePath: String,
    ): Boolean {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            println("Error: Source directory '$sourcePath' does not exist for zipping.")
            return false
        }

        // Ensure the parent directory for the archive exists
        val archiveParentDir = File(archiveFilePath).parentFile
        if (archiveParentDir != null && !archiveParentDir.exists()) {
            // Create parent directory using root, if needed
            // fastCmd returns empty string on success, non-empty on error
            val mkdirResult = fastCmd(getRootShell(),"mkdir -p \"${archiveParentDir.absolutePath}\"")
            if (mkdirResult.isNotEmpty()) { // Check if there was an error output
                println("Error creating archive parent directory: $mkdirResult")
                return false
            }
        }

        // Use 'tar' to create an archive and pipe it to 'xz' for compression.
        // -C $(dirname $sourcePath) changes directory before archiving,
        // so that the archive contains the basename of the sourcePath directly, not its full path.
        // Example: tar -C /data/data com.example.app would archive 'com.example.app' directly.
        val command = "cd \"${sourceFile.parentFile?.absolutePath ?: "/"}\" && tar -cf - \"${sourceFile.name}\" | xz > \"$archiveFilePath\""
        println("Executing zip command: $command")
        val result = fastCmd(getRootShell(),command)

        if (result.isEmpty()) { // Check if the command output was empty (indicating success)
            println("Successfully zipped '$sourcePath' to '$archiveFilePath'")
        } else {
            println("Failed to zip '$sourcePath': $result")
        }
        return result.isEmpty()
    }

    /**
     * Unzips a .tar.xz archive file to a specified destination directory using root shell commands.
     * This method assumes 'tar' and 'xz' utilities are available via the root shell.
     *
     * @param archiveFilePath The absolute path to the .tar.xz archive file.
     * @param destinationPath The absolute path to the directory where contents should be extracted.
     * @return True if unzipping was successful, false otherwise.
     */
    fun unzipFile(
        archiveFilePath: String,
        destinationPath: String,
    ): Boolean {
        val archiveFile = File(archiveFilePath)
        if (!archiveFile.exists()) {
            println("Error: Archive file '$archiveFilePath' does not exist for unzipping.")
            return false
        }

        // Ensure the destination directory exists
        val destDir = File(destinationPath)
        if (!destDir.exists()) {
            // fastCmd returns empty string on success, non-empty on error
            val mkdirResult = fastCmd(getRootShell(),"mkdir -p \"${destDir.absolutePath}\"")
            if (mkdirResult.isNotEmpty()) { // Check if there was an error output
                println("Error creating destination directory: $mkdirResult")
                return false
            }
        }

        // Use 'xz' to decompress and pipe to 'tar' for extraction.
        // -C $destinationPath extracts contents directly into the specified destination.
        val command = "xz -dck \"$archiveFilePath\" | tar -xf - -C \"$destinationPath\""
        println("Executing unzip command: $command")
        val result = fastCmd(getRootShell(),command)

        if (result.isEmpty()) { // Check if the command output was empty (indicating success)
            println("Successfully unzipped '$archiveFilePath' to '$destinationPath'")
        } else {
            println("Failed to unzip '$archiveFilePath': $result")
        }
        return result.isEmpty()
    }

    /**
     * Backs up an application's selected parts to a .tar.xz archive in a public directory.
     * Handles system app limitations (only APK backup).
     *
     * @param appInfo The AppInfo object of the application to backup.
     * @param selectedParts The set of BackupPart enums to include in the backup.
     * @param onWarning A callback function to notify the UI about warnings (e.g., system app limitations).
     * @return An AppBackupInfo object if backup is successful, null otherwise.
     */
    fun backupApp(
        appInfo: com.valhalla.thor.model.AppInfo, // Use the full qualified name for AppInfo
        selectedParts: Set<BackupPart>,
        onWarning: (message: String) -> Unit // Callback for warnings
    ): AppBackupInfo? {
        val packageName = appInfo.packageName
        val appName = appInfo.appName ?: packageName
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val backupDirName = "${appName.replace(" ", "_")}_${timestamp}"
        val backupAppBaseDir = "$PUBLIC_BACKUP_BASE_DIR/$backupDirName"
        val archiveFilePath = "$backupAppBaseDir/${packageName}_${timestamp}.tar.xz"
        val stagingDir = "/data/local/tmp/thor_backup_staging/${packageName}_${timestamp}_staging"

        val actualBackedUpParts = mutableSetOf<BackupPart>()

        // 1. Create the base backup directory in public storage
        var mkdirResult = fastCmd(getRootShell(),"mkdir -p \"$backupAppBaseDir\"")
        if (mkdirResult.isNotEmpty()) {
            println("Error creating public backup directory '$backupAppBaseDir': $mkdirResult")
            return null
        }

        // 2. Create the staging directory
        mkdirResult = fastCmd(getRootShell(),"mkdir -p \"$stagingDir\"")
        if (mkdirResult.isNotEmpty()) {
            println("Error creating staging directory '$stagingDir': $mkdirResult")
            // Attempt cleanup of created public backup dir
            fastCmd(getRootShell(), "rm -rf \"$backupAppBaseDir\"")
            return null
        }

        // Determine parts to backup based on system app status
        val partsToProcess = if (isSystemApp(appInfo.toApplicationInfo())) { // Convert your AppInfo to Android's ApplicationInfo
            if (selectedParts.contains(BackupPart.APK)) {
                onWarning("Warning: Only APK can be backed up for system app '$appName'. Other selected parts will be ignored.")
                setOf(BackupPart.APK)
            } else {
                onWarning("Warning: System app '$appName' can only have its APK backed up. No parts selected, so no backup will be created.")
                emptySet() // No APK selected, so no backup for system app
            }
        } else {
            selectedParts // For user apps, process all selected parts
        }

        if (partsToProcess.isEmpty()) {
            println("No valid parts selected for backup of app '$appName'. Aborting.")
            fastCmd(getRootShell(), "rm -rf \"$stagingDir\"") // Clean up staging
            fastCmd(getRootShell(), "rm -rf \"$backupAppBaseDir\"") // Clean up public backup dir
            return null
        }


        // 3. Copy selected parts to staging directory
        partsToProcess.forEach { part ->
            when (part) {
                BackupPart.APK -> {
                    getAppApkPaths(appInfo.toApplicationInfo()).forEachIndexed { index, apkPath ->
                        val apkFileName = File(apkPath).name
                        val destPath = "$stagingDir/apk/$apkFileName"
                        val copyCmd = "mkdir -p \"$stagingDir/apk\" && cp -f \"$apkPath\" \"$destPath\""
                        println("Copying APK: $copyCmd")
                        val result = fastCmd(getRootShell(), copyCmd)
                        if (result.isNotEmpty()) {
                            println("Error copying APK '$apkPath': $result")
                            onWarning("Failed to copy APK for '$appName'. Backup might be incomplete.")
                        } else {
                            actualBackedUpParts.add(BackupPart.APK)
                        }
                    }
                }
                BackupPart.DATA -> {
                    val dataPath = getAppDataPath(packageName)
                    // Use tar for internal data to preserve permissions and ownership
                    val destTarPath = "$stagingDir/data.tar" // Temporary tar file inside staging
                    val tarCommand = "cd \"${File(dataPath).parentFile?.absolutePath ?: "/"}\" && tar -cf \"$destTarPath\" \"${File(dataPath).name}\""
                    println("Archiving internal data: $tarCommand")
                    val result = fastCmd(getRootShell(), tarCommand)
                    if (result.isNotEmpty()) {
                        println("Error archiving internal data '$dataPath': $result")
                        onWarning("Failed to backup internal data for '$appName'. Backup might be incomplete.")
                    } else {
                        actualBackedUpParts.add(BackupPart.DATA)
                    }
                }
                BackupPart.EXT_DATA -> {
                    val extDataPath = getAppExternalDataPath(packageName)
                    val destPath = "$stagingDir/ext_data"
                    val copyCmd = "mkdir -p \"$destPath\" && cp -r \"$extDataPath\" \"$destPath/\""
                    println("Copying external data: $copyCmd")
                    val result = fastCmd(getRootShell(), copyCmd)
                    if (result.isNotEmpty()) {
                        println("Error copying external data '$extDataPath': $result")
                        onWarning("Failed to backup external data for '$appName'. Backup might be incomplete.")
                    } else {
                        actualBackedUpParts.add(BackupPart.EXT_DATA)
                    }
                }
                BackupPart.OBB -> {
                    val obbPath = getAppObbPath(packageName)
                    val destPath = "$stagingDir/obb"
                    val copyCmd = "mkdir -p \"$destPath\" && cp -r \"$obbPath\" \"$destPath/\""
                    println("Copying OBB: $copyCmd")
                    val result = fastCmd(getRootShell(), copyCmd)
                    if (result.isNotEmpty()) {
                        println("Error copying OBB '$obbPath': $result")
                        onWarning("Failed to backup OBB for '$appName'. Backup might be incomplete.")
                    } else {
                        actualBackedUpParts.add(BackupPart.OBB)
                    }
                }
                BackupPart.MEDIA -> {
                    getAppMediaPaths(packageName).forEach { mediaPath ->
                        val mediaDirName = File(mediaPath).name
                        val destPath = "$stagingDir/media/$mediaDirName"
                        val copyCmd = "mkdir -p \"$destPath\" && cp -r \"$mediaPath\" \"$destPath/\""
                        println("Copying media: $copyCmd")
                        val result = fastCmd(getRootShell(), copyCmd)
                        if (result.isNotEmpty()) {
                            println("Error copying media '$mediaPath': $result")
                            onWarning("Failed to backup media for '$appName'. Backup might be incomplete.")
                        } else {
                            actualBackedUpParts.add(BackupPart.MEDIA)
                        }
                    }
                }
            }
        }

        // Check if any parts were successfully copied to staging
        if (actualBackedUpParts.isEmpty()) {
            println("No parts were successfully staged for backup of app '$appName'. Aborting.")
            fastCmd(getRootShell(), "rm -rf \"$stagingDir\"") // Clean up staging
            fastCmd(getRootShell(), "rm -rf \"$backupAppBaseDir\"") // Clean up public backup dir
            return null
        }

        // 4. Zip the staging directory
        println("Zipping staging directory '$stagingDir' to '$archiveFilePath'")
        val zipSuccess = zipDirectory(stagingDir, archiveFilePath)

        // 5. Clean up staging directory
        val cleanupResult = fastCmd(getRootShell(),"rm -rf \"$stagingDir\"")
        if (cleanupResult.isNotEmpty()) {
            println("Warning: Failed to clean up staging directory '$stagingDir': $cleanupResult")
            onWarning("Failed to clean up temporary files. Manual cleanup might be required.")
        }

        if (!zipSuccess) {
            println("Final zip operation failed for '$appName'. Aborting.")
            fastCmd(getRootShell(), "rm -rf \"$archiveFilePath\"") // Remove incomplete archive
            fastCmd(getRootShell(), "rm -rf \"$backupAppBaseDir\"") // Remove empty backup dir
            return null
        }

        // 6. Calculate final backup size
        val backupSizeResult = fastCmd(getRootShell(),
            "du -sb \"$archiveFilePath\" | awk '{print $1}'"
        )
        val backupSize = backupSizeResult.trim().toLongOrNull() ?: 0L

        // 7. Create and return AppBackupInfo
        return AppBackupInfo(
            packageName = packageName,
            appName = appName,
            versionCode = appInfo.versionCode.toLong(), // Assuming AppInfo.versionCode is Int, convert to Long
            versionName = appInfo.versionName ?: "N/A",
            backupTime = System.currentTimeMillis(),
            backupLocation = archiveFilePath,
            backedUpParts = actualBackedUpParts,
            backupSize = backupSize,
            isSystemApp = isSystemApp(appInfo.toApplicationInfo()), // Re-check system app status
            isSplitApk = appInfo.splitPublicSourceDirs.isNotEmpty()
        )
    }

    // Extension function to convert your AppInfo to Android's ApplicationInfo
    // This is a simplified conversion and might need more fields depending on usage.
    // Ideally, your AppInfoGrabber would directly return ApplicationInfo or a more complete wrapper.
    private fun com.valhalla.thor.model.AppInfo.toApplicationInfo(): ApplicationInfo {
        val appInfo = ApplicationInfo()
        appInfo.packageName = this.packageName
        appInfo.sourceDir = this.sourceDir
        appInfo.publicSourceDir = this.publicSourceDir
        appInfo.splitSourceDirs = this.splitPublicSourceDirs.toTypedArray()
        appInfo.dataDir = this.dataDir
        appInfo.flags = if (this.isSystem) ApplicationInfo.FLAG_SYSTEM else 0
        // Add other relevant fields if needed by getAppApkPaths or isSystemApp
        return appInfo
    }



}