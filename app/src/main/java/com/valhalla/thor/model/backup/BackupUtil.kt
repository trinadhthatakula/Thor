@file:Suppress("unused")

package com.valhalla.thor.model.backup

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import com.valhalla.superuser.ShellUtils.fastCmd
import com.valhalla.thor.model.getRootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


/**
 * Sealed class representing the various states of a backup or restore operation.
 * This allows for granular updates to the UI.
 */
sealed class BackupOperationState {
    data object Idle : BackupOperationState()
    data class Progress(val percentage: Int, val message: String) : BackupOperationState()
    data class Success(val backupInfo: AppBackupInfo) : BackupOperationState()
    data class Error(val message: String, val cause: Throwable? = null) : BackupOperationState()
    data class Warning(val message: String) : BackupOperationState() // For non-fatal issues
}


/**
 * Object class providing utility functions for file paths and (future) zip/unzip operations.
 * This class encapsulates logic for determining standard Android app data paths.
 */
@SuppressLint("SdCardPath")
object BackupUtil {

    // Base paths for various app data locations.
    // These paths are standard on Android and typically require root for access to other apps' data.
    private const val DATA_APP_DIR = "/data/app"
    private const val DATA_DATA_DIR = "/data/data"
    private const val SDCARD_ANDROID_DATA_DIR = "/sdcard/Android/data"
    private const val SDCARD_ANDROID_OBB_DIR = "/sdcard/Android/obb"
    // Note: "/sdcard" is often a symlink to "/storage/emulated/0" on modern Android devices.
    private const val PUBLIC_BACKUP_BASE_DIR = "/sdcard/thor/backup" // Publicly accessible backup folder
    private val json = Json { prettyPrint = true } // For pretty printing JSON metadata

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
     * Saves the AppBackupInfo metadata to a JSON file within the backup directory.
     *
     * @param backupInfo The AppBackupInfo object to save.
     * @return True if metadata was saved successfully, false otherwise.
     */
    private fun saveBackupMetadata(backupInfo: AppBackupInfo): Boolean {
        val metadataFile = File(backupInfo.backupLocation, "metadata.json")
        return try {
            val jsonString = json.encodeToString(backupInfo)
            // Use root command to write to file to ensure permissions
            val writeCommand = "echo \"$jsonString\" > \"${metadataFile.absolutePath}\""
            val result = fastCmd(getRootShell(), writeCommand)
            if (result.isNotEmpty()) {
                println("Error saving metadata to '${metadataFile.absolutePath}': $result")
                false
            } else {
                println("Metadata saved to '${metadataFile.absolutePath}'")
                true
            }
        } catch (e: Exception) {
            println("Exception saving metadata: ${e.message}")
            false
        }
    }

    /**
     * Loads AppBackupInfo metadata from a JSON file within a backup directory.
     *
     * @param backupDirPath The absolute path to the backup directory.
     * @return The AppBackupInfo object if loaded successfully, null otherwise.
     */
    private fun loadBackupMetadata(backupDirPath: String): AppBackupInfo? {
        val metadataFile = File(backupDirPath, "metadata.json")
        if (!metadataFile.exists()) {
            println("Metadata file not found at '${metadataFile.absolutePath}'")
            return null
        }
        return try {
            val readCommand = "cat \"${metadataFile.absolutePath}\""
            val result = fastCmd(getRootShell(), readCommand)
            if (result.isNotEmpty()) { // If there's an error reading
                println("Error reading metadata from '${metadataFile.absolutePath}': $result")
                null
            } else {
                json.decodeFromString<AppBackupInfo>(result)
            }
        } catch (e: Exception) {
            println("Exception loading metadata from '${metadataFile.absolutePath}': ${e.message}")
            null
        }
    }

    /**
     * Backs up an application's selected parts to a dedicated directory in a public folder.
     * This backup is uncompressed by default. Handles system app limitations (only APK backup).
     * Emits states via a Flow for UI updates.
     *
     * @param appInfo The AppInfo object of the application to backup.
     * @param selectedParts The set of BackupPart enums to include in the backup.
     * @return A Flow emitting BackupOperationState objects (Progress, Success, Error, Warning).
     */
    fun backupApp(
        appInfo: com.valhalla.thor.model.AppInfo, // Use the full qualified name for AppInfo
        selectedParts: Set<BackupPart>
    ): Flow<BackupOperationState> = flow {
        emit(BackupOperationState.Progress(0, "Starting backup for ${appInfo.appName}..."))

        val packageName = appInfo.packageName
        val appName = appInfo.appName ?: packageName
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val backupDirName = "${appName.replace(" ", "_")}_${timestamp}"
        val backupAppBaseDir = "$PUBLIC_BACKUP_BASE_DIR/$backupDirName" // This is now the backup location

        val actualBackedUpParts = mutableSetOf<BackupPart>()

        // 1. Create the base backup directory in public storage
        emit(BackupOperationState.Progress(5, "Creating backup directory..."))
        val mkdirResult = fastCmd(getRootShell(),"mkdir -p \"$backupAppBaseDir\"")
        if (mkdirResult.isNotEmpty()) {
            val errorMessage = "Error creating public backup directory '$backupAppBaseDir': $mkdirResult"
            println(errorMessage)
            emit(BackupOperationState.Error(errorMessage))
            return@flow
        }

        // Determine parts to backup based on system app status
        val partsToProcess = if (isSystemApp(appInfo.toApplicationInfo())) {
            if (selectedParts.contains(BackupPart.APK)) {
                emit(BackupOperationState.Warning("Only APK can be backed up for system app '$appName'. Other selected parts will be ignored."))
                setOf(BackupPart.APK)
            } else {
                emit(BackupOperationState.Warning("System app '$appName' can only have its APK backed up. No parts selected, so no backup will be created."))
                emptySet()
            }
        } else {
            selectedParts
        }

        if (partsToProcess.isEmpty()) {
            val message = "No valid parts selected for backup of app '$appName'. Aborting."
            println(message)
            fastCmd(getRootShell(), "rm -rf \"$backupAppBaseDir\"") // Clean up empty backup dir
            emit(BackupOperationState.Error(message)) // Consider this an error as no backup was made
            return@flow
        }

        // 2. Copy selected parts into subdirectories within backupAppBaseDir
        val totalParts = partsToProcess.size
        var partsCompleted = 0

        partsToProcess.forEach { part ->
            val partProgress = 10 + (partsCompleted * 80 / totalParts) // Allocate 80% for copying parts
            emit(BackupOperationState.Progress(partProgress, "Backing up ${part.name} for ${appName}..."))

            when (part) {
                BackupPart.APK -> {
                    val apkDestDir = "$backupAppBaseDir/apk"
                    val createApkDirResult = fastCmd(getRootShell(), "mkdir -p \"$apkDestDir\"")
                    if (createApkDirResult.isNotEmpty()) {
                        val warningMessage = "Failed to create APK directory '$apkDestDir' for '$appName'. APK backup might be incomplete."
                        println("Error: $warningMessage")
                        emit(BackupOperationState.Warning(warningMessage))
                        return@forEach // Skip this part
                    }
                    getAppApkPaths(appInfo.toApplicationInfo()).forEach { apkPath ->
                        val apkFileName = File(apkPath).name
                        val destPath = "$apkDestDir/$apkFileName"
                        val copyCmd = "cp -f \"$apkPath\" \"$destPath\""
                        val result = fastCmd(getRootShell(), copyCmd)
                        if (result.isNotEmpty()) {
                            val warningMessage = "Error copying APK '$apkPath': $result. Backup might be incomplete."
                            println(warningMessage)
                            emit(BackupOperationState.Warning(warningMessage))
                        } else {
                            actualBackedUpParts.add(BackupPart.APK)
                        }
                    }
                }
                BackupPart.DATA -> {
                    val dataPath = getAppDataPath(packageName)
                    val dataDestDir = "$backupAppBaseDir/data"
                    val createDataDirResult = fastCmd(getRootShell(), "mkdir -p \"$dataDestDir\"")
                    if (createDataDirResult.isNotEmpty()) {
                        val warningMessage = "Failed to create DATA directory '$dataDestDir' for '$appName'. Data backup might be incomplete."
                        println("Error: $warningMessage")
                        emit(BackupOperationState.Warning(warningMessage))
                        return@forEach
                    }
                    // Use tar for internal data to preserve permissions and ownership
                    val destTarPath = "$dataDestDir/${packageName}_data.tar" // Tar file inside data subdir
                    val tarCommand = "cd \"${File(dataPath).parentFile?.absolutePath ?: "/"}\" && tar -cf \"$destTarPath\" \"${File(dataPath).name}\""
                    val result = fastCmd(getRootShell(), tarCommand)
                    if (result.isNotEmpty()) {
                        val warningMessage = "Error archiving internal data '$dataPath': $result. Backup might be incomplete."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    } else {
                        actualBackedUpParts.add(BackupPart.DATA)
                    }
                }
                BackupPart.EXT_DATA -> {
                    val extDataPath = getAppExternalDataPath(packageName)
                    val extDataDestDir = "$backupAppBaseDir/ext_data"
                    val createExtDataDirResult = fastCmd(getRootShell(), "mkdir -p \"$extDataDestDir\"")
                    if (createExtDataDirResult.isNotEmpty()) {
                        val warningMessage = "Failed to create EXT_DATA directory '$extDataDestDir' for '$appName'. External data backup might be incomplete."
                        println("Error: $warningMessage")
                        emit(BackupOperationState.Warning(warningMessage))
                        return@forEach
                    }
                    val copyCmd = "cp -r \"$extDataPath\" \"$extDataDestDir/\""
                    val result = fastCmd(getRootShell(), copyCmd)
                    if (result.isNotEmpty()) {
                        val warningMessage = "Error copying external data '$extDataPath': $result. Backup might be incomplete."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    } else {
                        actualBackedUpParts.add(BackupPart.EXT_DATA)
                    }
                }
                BackupPart.OBB -> {
                    val obbPath = getAppObbPath(packageName)
                    val obbDestDir = "$backupAppBaseDir/obb"
                    val createObbDirResult = fastCmd(getRootShell(), "mkdir -p \"$obbDestDir\"")
                    if (createObbDirResult.isNotEmpty()) {
                        val warningMessage = "Failed to create OBB directory '$obbDestDir' for '$appName'. OBB backup might be incomplete."
                        println("Error: $warningMessage")
                        emit(BackupOperationState.Warning(warningMessage))
                        return@forEach
                    }
                    val copyCmd = "cp -r \"$obbPath\" \"$obbDestDir/\""
                    val result = fastCmd(getRootShell(), copyCmd)
                    if (result.isNotEmpty()) {
                        val warningMessage = "Error copying OBB '$obbPath': $result. Backup might be incomplete."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    } else {
                        actualBackedUpParts.add(BackupPart.OBB)
                    }
                }
                BackupPart.MEDIA -> {
                    val mediaDestDir = "$backupAppBaseDir/media"
                    val createMediaDirResult = fastCmd(getRootShell(), "mkdir -p \"$mediaDestDir\"")
                    if (createMediaDirResult.isNotEmpty()) {
                        val warningMessage = "Failed to create MEDIA directory '$mediaDestDir' for '$appName'. Media backup might be incomplete."
                        println("Error: $warningMessage")
                        emit(BackupOperationState.Warning(warningMessage))
                        return@forEach
                    }
                    getAppMediaPaths(packageName).forEach { mediaPath ->
                        val mediaDirName = File(mediaPath).name
                        val copyCmd = "cp -r \"$mediaPath\" \"$mediaDestDir/$mediaDirName\""
                        val result = fastCmd(getRootShell(), copyCmd)
                        if (result.isNotEmpty()) {
                            val warningMessage = "Error copying media '$mediaPath': $result. Backup might be incomplete."
                            println(warningMessage)
                            emit(BackupOperationState.Warning(warningMessage))
                        } else {
                            actualBackedUpParts.add(BackupPart.MEDIA)
                        }
                    }
                }
            }
            partsCompleted++
        }

        // Check if any parts were successfully copied
        if (actualBackedUpParts.isEmpty()) {
            val message = "No parts were successfully backed up for app '$appName'. Aborting."
            println(message)
            fastCmd(getRootShell(), "rm -rf \"$backupAppBaseDir\"") // Clean up empty backup dir
            emit(BackupOperationState.Error(message))
            return@flow
        }

        // 3. Calculate final backup size (of the uncompressed directory)
        emit(BackupOperationState.Progress(90, "Calculating backup size..."))
        val backupSizeResult = fastCmd(getRootShell(),
            "du -sb \"$backupAppBaseDir\" | awk '{print $1}'"
        )
        val backupSize = backupSizeResult.trim().toLongOrNull() ?: 0L

        // 4. Create AppBackupInfo and save its metadata
        val newBackupInfo = AppBackupInfo(
            packageName = packageName,
            appName = appName,
            versionCode = appInfo.versionCode.toLong(),
            versionName = appInfo.versionName ?: "N/A",
            backupTime = System.currentTimeMillis(),
            backupLocation = backupAppBaseDir, // Now points to the directory
            backedUpParts = actualBackedUpParts,
            backupSize = backupSize,
            isSystemApp = isSystemApp(appInfo.toApplicationInfo()),
            isSplitApk = appInfo.splitPublicSourceDirs.isNotEmpty(),
            archiveFilePath = null // Initially null, will be set if compressed later
        )

        emit(BackupOperationState.Progress(95, "Saving backup metadata..."))
        if (!saveBackupMetadata(newBackupInfo)) {
            val errorMessage = "Error: Failed to save backup metadata for '$appName'. Deleting backup directory."
            println(errorMessage)
            fastCmd(getRootShell(), "rm -rf \"$backupAppBaseDir\"")
            emit(BackupOperationState.Error(errorMessage))
            return@flow
        }

        println("Successfully created uncompressed backup for '$appName' at '$backupAppBaseDir'")
        emit(BackupOperationState.Success(newBackupInfo))
    }.flowOn(Dispatchers.IO) // Run the heavy operations on IO dispatcher

    /**
     * Compresses an existing uncompressed app backup directory into a .tar.xz archive.
     * Emits states via a Flow for UI updates.
     *
     * @param backupInfo The AppBackupInfo object of the uncompressed backup.
     * @return A Flow emitting BackupOperationState objects (Progress, Success, Error, Warning).
     */
    fun compressBackup(
        backupInfo: AppBackupInfo,
    ): Flow<BackupOperationState> = flow {
        if (backupInfo.archiveFilePath != null) {
            val message = "Backup for '${backupInfo.appName}' is already compressed."
            println(message)
            emit(BackupOperationState.Warning(message))
            emit(BackupOperationState.Success(backupInfo)) // Still a success, but with a warning
            return@flow
        }

        emit(BackupOperationState.Progress(0, "Starting compression for ${backupInfo.appName}..."))

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val archiveFileName = "${backupInfo.packageName}_${timestamp}.tar.xz"
        // Place archive in the parent directory of the original backup directory
        val archiveFilePath = File(backupInfo.backupLocation).parentFile?.absolutePath + "/$archiveFileName"
        val metadataFilePath = File(backupInfo.backupLocation).parentFile?.absolutePath + "/${backupInfo.packageName}_${timestamp}.json"


        val zipSuccess = zipDirectory(backupInfo.backupLocation, archiveFilePath)

        if (zipSuccess) {
            emit(BackupOperationState.Progress(80, "Compression successful. Updating metadata..."))
            // Update backupInfo with archive path and new size
            val newBackupSizeResult = fastCmd(getRootShell(),
                "du -sb \"$archiveFilePath\" | awk '{print $1}'"
            )
            val newBackupSize = newBackupSizeResult.trim().toLongOrNull() ?: 0L

            val updatedBackupInfo = backupInfo.copy(
                archiveFilePath = archiveFilePath,
                backupSize = newBackupSize,
                // The backupLocation for the AppBackupInfo now points to the compressed file
                // and we will clean up the directory, so we should null out the location
                backupLocation = metadataFilePath.substringBeforeLast(".json")
            )
            // Remove the original uncompressed directory after successful compression
            val rmResult = fastCmd(getRootShell(), "rm -rf \"${backupInfo.backupLocation}\"")
            if (rmResult.isNotEmpty()) {
                val warningMessage = "Warning: Failed to remove uncompressed backup directory after compression: $rmResult"
                println(warningMessage)
                emit(BackupOperationState.Warning(warningMessage))
            }

            // Save the updated metadata to the new location (next to the .tar.xz file)
            val jsonString = json.encodeToString(updatedBackupInfo)
            val saveResult = fastCmd(getRootShell(), "echo \"$jsonString\" > \"$metadataFilePath\"")

            if (saveResult.isEmpty()) {
                emit(BackupOperationState.Progress(100, "Compression complete!"))
                emit(BackupOperationState.Success(updatedBackupInfo))
            } else {
                val errorMessage = "Error: Failed to save updated metadata after compression. Keeping both compressed and uncompressed."
                println(errorMessage)
                emit(BackupOperationState.Error(errorMessage))
                // Clean up potentially incomplete archive if metadata save failed
                fastCmd(getRootShell(), "rm -f \"$archiveFilePath\"")
            }
        } else {
            val errorMessage = "Compression failed for '${backupInfo.appName}'."
            println(errorMessage)
            emit(BackupOperationState.Error(errorMessage))
            // Clean up potentially incomplete archive
            fastCmd(getRootShell(), "rm -f \"$archiveFilePath\"")
        }
    }.flowOn(Dispatchers.IO) // Run on IO dispatcher

    /**
     * Scans the public backup directory and returns a list of all detected AppBackupInfo objects.
     * This function reads the metadata.json file within each backup directory.
     *
     * @return A list of AppBackupInfo objects found.
     */
    fun listBackups(): List<AppBackupInfo> {
        val backups = mutableListOf<AppBackupInfo>()
        val backupBaseDirFile = File(PUBLIC_BACKUP_BASE_DIR)
        if (!backupBaseDirFile.exists() || !backupBaseDirFile.isDirectory) {
            println("Backup base directory does not exist or is not a directory: $PUBLIC_BACKUP_BASE_DIR")
            return emptyList()
        }

        // We can use a combination of File.listFiles() and `fastCmd` for robust metadata retrieval.
        // File.listFiles() is faster, but `fastCmd` might be needed for hidden/inaccessible files.
        // Let's use a combination to be safe.
        val lsResult = fastCmd(getRootShell(), "ls -a \"$PUBLIC_BACKUP_BASE_DIR\"")
        if (lsResult.isNotEmpty()) {
            val fileNames = lsResult.lines().filter { it.isNotBlank() }
            fileNames.forEach { fileName ->
                val fullPath = "$PUBLIC_BACKUP_BASE_DIR/$fileName"
                if (fileName.endsWith(".json")) {
                    val archiveFilePath = fullPath.substringBeforeLast(".json") + ".tar.xz"
                    val archiveFile = File(archiveFilePath)
                    if (archiveFile.exists()) {
                        // Found a metadata file for a compressed archive
                        try {
                            val readCommand = "cat \"$fullPath\""
                            val metadataJson = fastCmd(getRootShell(), readCommand)
                            if (metadataJson.isNotEmpty()) {
                                json.decodeFromString<AppBackupInfo>(metadataJson).let { backupInfo ->
                                    // Ensure the AppBackupInfo has the correct path to the archive file
                                    backups.add(backupInfo.copy(archiveFilePath = archiveFilePath))
                                }
                            }
                        } catch (e: Exception) {
                            println("Error loading metadata from '$fullPath': ${e.message}")
                        }
                    }
                } else if (File(fullPath).isDirectory) {
                    // Found an uncompressed backup directory
                    loadBackupMetadata(fullPath)?.let { backupInfo ->
                        backups.add(backupInfo)
                    }
                }
            }
        }
        return backups.sortedByDescending { it.backupTime }
    }

    /**
     * Restores an application from a backup.
     * Emits states via a Flow for UI updates.
     *
     * @param backupInfo The AppBackupInfo object of the backup to restore.
     * @param partsToRestore The specific parts to restore from the backup.
     * @param overrideExistingInstallation If true, will attempt to override an existing app installation.
     * @param onConfirmation A callback to request user confirmation for actions like overriding.
     * Returns true if user confirms, false otherwise.
     * @return A Flow emitting BackupOperationState objects (Progress, Success, Error, Warning).
     */
    fun restoreApp(
        backupInfo: AppBackupInfo,
        partsToRestore: Set<BackupPart>,
        overrideExistingInstallation: Boolean,
        onConfirmation: ((message: String) -> Boolean)? = null // Callback for user confirmation
    ): Flow<BackupOperationState> = flow {
        emit(BackupOperationState.Progress(0, "Starting restoration for ${backupInfo.appName}..."))

        val packageName = backupInfo.packageName
        val appName = backupInfo.appName

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())

        // Determine the source directory for restoration (uncompressed backup or temporary unzipped)
        var sourceBackupDir = backupInfo.backupLocation
        var tempUnzipDir: String? = null

        // 1. If the backup is compressed, unzip it first
        if (backupInfo.archiveFilePath != null) {
            emit(BackupOperationState.Progress(5, "Decompressing backup archive..."))
            tempUnzipDir = "/data/local/tmp/thor_restore_staging/${packageName}_${System.currentTimeMillis()}_restore"
            val unzipSuccess = unzipFile(backupInfo.archiveFilePath, tempUnzipDir)
            if (!unzipSuccess) {
                val errorMessage = "Failed to decompress archive '${backupInfo.archiveFilePath}'."
                println(errorMessage)
                emit(BackupOperationState.Error(errorMessage))
                // Clean up temp dir if created
                fastCmd(getRootShell(), "rm -rf \"$tempUnzipDir\"")
                return@flow
            }
            sourceBackupDir = tempUnzipDir // Use the unzipped directory as the source
        }

        // 2. Check if app is currently installed
        val isAppInstalled = fastCmd(getRootShell(), "pm path $packageName").isNotEmpty()

        if (isAppInstalled && !overrideExistingInstallation) {
            val message = "App '$appName' is already installed. Do you want to override it?"
            val confirmed = onConfirmation?.invoke(message) ?: false
            if (!confirmed) {
                val errorMessage = "Restoration aborted by user: App '$appName' already installed."
                println(errorMessage)
                // Clean up temp dir if created
                tempUnzipDir?.let { fastCmd(getRootShell(), "rm -rf \"$it\"") }
                emit(BackupOperationState.Error(errorMessage))
                return@flow
            }
        }

        // 3. Force stop the app if it's installed, especially for data restoration
        if (isAppInstalled) {
            emit(BackupOperationState.Progress(10, "Force stopping app '${appName}'..."))
            val forceStopResult = fastCmd(getRootShell(), "am force-stop $packageName")
            if (forceStopResult.isNotEmpty()) {
                val warningMessage = "Warning: Failed to force stop app '$appName': $forceStopResult. Data restoration might be unstable."
                println(warningMessage)
                emit(BackupOperationState.Warning(warningMessage))
            }
        }

        // 4. Restore selected parts
        val totalParts = partsToRestore.size
        var partsRestored = 0

        partsToRestore.forEach { part ->
            val partProgress = 15 + (partsRestored * 70 / totalParts) // Allocate 70% for restoring parts
            emit(BackupOperationState.Progress(partProgress, "Restoring ${part.name} for ${appName}..."))

            when (part) {
                BackupPart.APK -> {
                    val apkSourceDir = "$sourceBackupDir/apk"
                    val apkFilesResult = fastCmd(getRootShell(), "ls \"$apkSourceDir\"/*.apk")
                    if (apkFilesResult.isNotEmpty()) {
                        val apkPaths = apkFilesResult.lines().filter { it.isNotBlank() }
                        val installCommand = if (apkPaths.size > 1) {
                            "pm install-multiple ${if (overrideExistingInstallation) "-r " else ""}${apkPaths.joinToString(" ")}"
                        } else {
                            "pm install ${if (overrideExistingInstallation) "-r " else ""}\"${apkPaths.first()}\""
                        }
                        println("Installing APK: $installCommand")
                        val installResult = fastCmd(getRootShell(), installCommand)
                        if (installResult.isNotEmpty() && !installResult.contains("Success")) { // pm install outputs "Success" on stdout
                            val errorMessage = "Failed to install APK for '$appName': $installResult"
                            println(errorMessage)
                            emit(BackupOperationState.Error(errorMessage))
                            // Don't return@flow here, try to restore other parts if possible
                        } else {
                            println("APK installed successfully for '$appName'.")
                        }
                    } else {
                        val warningMessage = "No APK files found in backup for '$appName'."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    }
                }
                BackupPart.DATA -> {
                    val dataTarPath = "$sourceBackupDir/data/${packageName}_data.tar"
                    if (File(dataTarPath).exists()) {
                        // Delete existing app data directory before restoring
                        val deleteDataCmd = "rm -rf \"${getAppDataPath(packageName)}\""
                        val deleteResult = fastCmd(getRootShell(), deleteDataCmd)
                        if (deleteResult.isNotEmpty()) {
                            val warningMessage = "Warning: Failed to clear existing app data for '$appName': $deleteResult. Restoration might be incomplete."
                            println(warningMessage)
                            emit(BackupOperationState.Warning(warningMessage))
                        }

                        // Untar the data
                        val untarCommand = "tar -xf \"$dataTarPath\" -C \"${File(getAppDataPath(packageName)).parentFile?.absolutePath ?: "/"}\""
                        println("Untarring internal data: $untarCommand")
                        val untarResult = fastCmd(getRootShell(), untarCommand)
                        if (untarResult.isNotEmpty()) {
                            val errorMessage = "Failed to restore internal data for '$appName': $untarResult"
                            println(errorMessage)
                            emit(BackupOperationState.Error(errorMessage))
                        } else {
                            // Set correct permissions and ownership. Find app's UID/GID.
                            // This is crucial for app to function correctly after data restore.
                            val uidResult = fastCmd(getRootShell(), "stat -c '%u:%g' \"${getAppDataPath(packageName)}\"")
                            val uidGid = uidResult.trim()
                            if (uidGid.isNotEmpty()) {
                                val chownCmd = "chown -R $uidGid \"${getAppDataPath(packageName)}\""
                                val chmodCmd = "chmod -R 771 \"${getAppDataPath(packageName)}\"" // Common permissions for app data
                                val chownResult = fastCmd(getRootShell(), chownCmd)
                                val chmodResult = fastCmd(getRootShell(), chmodCmd)
                                if (chownResult.isNotEmpty() || chmodResult.isNotEmpty()) {
                                    val warningMessage = "Warning: Failed to set permissions/ownership for '$appName' data. App might not function correctly. Chown: $chownResult, Chmod: $chmodResult"
                                    println(warningMessage)
                                    emit(BackupOperationState.Warning(warningMessage))
                                } else {
                                    println("Permissions/ownership set for '$appName' data.")
                                }
                            } else {
                                val warningMessage = "Warning: Could not determine UID/GID for '$appName'. Permissions/ownership not set."
                                println(warningMessage)
                                emit(BackupOperationState.Warning(warningMessage))
                            }
                        }
                    } else {
                        val warningMessage = "Internal data archive not found for '$appName'."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    }
                }
                BackupPart.EXT_DATA -> {
                    val extDataSourceDir = "$sourceBackupDir/ext_data/${packageName}" // Assuming ext_data contains a folder with package name
                    val extDataDestPath = getAppExternalDataPath(packageName)
                    if (File(extDataSourceDir).exists()) {
                        val copyCmd = "cp -r \"$extDataSourceDir\" \"${File(extDataDestPath).parentFile?.absolutePath}/\""
                        val result = fastCmd(getRootShell(), copyCmd)
                        if (result.isNotEmpty()) {
                            val errorMessage = "Failed to restore external data for '$appName': $result"
                            println(errorMessage)
                            emit(BackupOperationState.Error(errorMessage))
                        }
                    } else {
                        val warningMessage = "External data not found in backup for '$appName'."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    }
                }
                BackupPart.OBB -> {
                    val obbSourceDir = "$sourceBackupDir/obb/${packageName}" // Assuming obb contains a folder with package name
                    val obbDestPath = getAppObbPath(packageName)
                    if (File(obbSourceDir).exists()) {
                        val copyCmd = "cp -r \"$obbSourceDir\" \"${File(obbDestPath).parentFile?.absolutePath}/\""
                        val result = fastCmd(getRootShell(), copyCmd)
                        if (result.isNotEmpty()) {
                            val errorMessage = "Failed to restore OBB for '$appName': $result"
                            println(errorMessage)
                            emit(BackupOperationState.Error(errorMessage))
                        }
                    } else {
                        val warningMessage = "OBB files not found in backup for '$appName'."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    }
                }
                BackupPart.MEDIA -> {
                    val mediaSourceDir = "$sourceBackupDir/media"
                    // Media restoration is tricky as original paths are varied.
                    // For simplicity, copy to a general media folder or prompt user.
                    // For now, let's copy to a subfolder in Pictures.
                    val destMediaDir = "/sdcard/Pictures/ThorRestoredMedia/${appName.replace(" ", "_")}_${timestamp}"
                    val createMediaDestDir = fastCmd(getRootShell(), "mkdir -p \"$destMediaDir\"")
                    if (createMediaDestDir.isNotEmpty()) {
                        val warningMessage = "Failed to create destination media directory '$destMediaDir': $createMediaDestDir. Media restoration might be incomplete."
                        println(warningMessage)
                        emit(BackupOperationState.Warning(warningMessage))
                    } else {
                        val copyCmd = "cp -r \"$mediaSourceDir\"/* \"$destMediaDir/\"" // Copy contents
                        val result = fastCmd(getRootShell(), copyCmd)
                        if (result.isNotEmpty()) {
                            val errorMessage = "Failed to restore media for '$appName': $result"
                            println(errorMessage)
                            emit(BackupOperationState.Error(errorMessage))
                        } else {
                            emit(BackupOperationState.Warning("Media for '$appName' restored to '$destMediaDir'. Please check manually."))
                        }
                    }
                }
            }
            partsRestored++
        }

        // 5. Clean up temporary unzipped directories if applicable
        tempUnzipDir?.let {
            emit(BackupOperationState.Progress(95, "Cleaning up temporary files..."))
            val cleanupResult = fastCmd(getRootShell(), "rm -rf \"$it\"")
            if (cleanupResult.isNotEmpty()) {
                val warningMessage = "Warning: Failed to clean up temporary directory '$it': $cleanupResult"
                println(warningMessage)
                emit(BackupOperationState.Warning(warningMessage))
            }
        }

        emit(BackupOperationState.Progress(100, "Restoration complete!"))
        emit(BackupOperationState.Success(backupInfo)) // Emit the original backup info on success
    }.flowOn(Dispatchers.IO) // Ensure heavy operations run on IO dispatcher


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