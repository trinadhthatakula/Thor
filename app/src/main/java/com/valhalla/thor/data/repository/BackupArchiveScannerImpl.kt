// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.valhalla.thor.domain.model.escapeShellArg
import com.valhalla.thor.domain.repository.BackupArchiveItem
import com.valhalla.thor.domain.repository.BackupArchiveKind
import com.valhalla.thor.domain.repository.BackupArchiveScanner
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [BackupArchiveScanner::class])
class BackupArchiveScannerImpl(
    private val context: Context,
    private val preferences: PreferenceRepository,
    private val systemRepository: SystemRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : BackupArchiveScanner {

    override fun scanBackups(): Flow<List<BackupArchiveItem>> = flow {
        val items = mutableListOf<BackupArchiveItem>()
        val seenUris = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()

        // 0. Ensure directory exists and permissions across Downloads/Thor
        val extPath = Environment.getExternalStorageDirectory().absolutePath
        val thorDir = "$extPath/Download/$THOR_DOWNLOADS_SUBDIR"
        runCatching {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                THOR_DOWNLOADS_SUBDIR
            ).mkdirs()
        }
        val safeThorDir = thorDir.escapeShellArg()
        systemRepository.executeShellCommand(
            "mkdir -p $safeThorDir && chmod 755 $safeThorDir 2>/dev/null"
        )

        // 1. Direct filesystem scan for Downloads/Thor
        val fsItems = queryFilesystemDownloads(seenUris, seenKeys)
        items.addAll(fsItems)

        // 2. Privileged shell scan (Shizuku / Root) to discover non-owned files across Downloads/Thor
        val shellItems = queryShellDownloads(seenUris, seenKeys)
        items.addAll(shellItems)

        // 3. MediaStore query (Downloads and Files tables) on Q+
        val msItems = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStoreDownloads(seenUris, seenKeys)
        } else {
            emptyList()
        }
        items.addAll(msItems)

        // 4. Custom SAF tree if configured
        val customTreeUri = preferences.userPreferences.first().exportDirUri
        val treeItems = if (!customTreeUri.isNullOrBlank()) {
            queryCustomTree(customTreeUri, seenUris, seenKeys)
        } else {
            emptyList()
        }
        items.addAll(treeItems)

        items.sortByDescending { it.dateModifiedEpochSec }
        Logger.i(
            "BackupArchiveScanner",
            "Discovered ${items.size} items (filesystem=${fsItems.size}, shell=${shellItems.size}, mediaStore=${msItems.size}, customTree=${treeItems.size}): ${items.map { it.displayName }}"
        )
        emit(items)
    }.flowOn(ioDispatcher)

    override suspend fun deleteArchive(item: BackupArchiveItem): Boolean =
        withContext(ioDispatcher) {
            try {
                val uri = item.uriString.toUri()
                if (uri.scheme == "content") {
                    if (DocumentsContract.isDocumentUri(context, uri)) {
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        doc?.delete() ?: false
                    } else {
                        context.contentResolver.delete(uri, null, null) > 0
                    }
                } else if (uri.scheme == "file") {
                    val path = uri.path ?: return@withContext false
                    val f = File(path)
                    if (!f.delete()) {
                        val safePath = path.escapeShellArg()
                        systemRepository.executeShellCommand("rm -f $safePath").getOrNull()?.first == 0
                    } else {
                        true
                    }
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun queryShellDownloads(
        seenUris: MutableSet<String>,
        seenKeys: MutableSet<String>,
    ): List<BackupArchiveItem> {
        val results = mutableListOf<BackupArchiveItem>()
        val extPath = Environment.getExternalStorageDirectory().absolutePath
        val candidateDirs = listOf(
            "$extPath/Download/$THOR_DOWNLOADS_SUBDIR",
            "$extPath/Downloads/$THOR_DOWNLOADS_SUBDIR",
            "/storage/emulated/0/Download/$THOR_DOWNLOADS_SUBDIR",
            "/storage/emulated/0/Downloads/$THOR_DOWNLOADS_SUBDIR",
        ).distinct()

        for (dir in candidateDirs) {
            val safeDir = dir.escapeShellArg()
            val cmd = "stat -c '%s\t%Y\t%n' $safeDir/* 2>/dev/null"
            val res = systemRepository.executeShellCommand(cmd).getOrNull()
            if (res != null && res.first == 0 && !res.second.isNullOrBlank()) {
                val lines = res.second!!.lines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) continue
                    val parts = trimmed.split('\t', limit = 3)
                    if (parts.size < 3) continue
                    val size = parts[0].toLongOrNull() ?: continue
                    val mtime = parts[1].toLongOrNull() ?: (System.currentTimeMillis() / 1000)
                    val fullPath = parts[2]
                    val file = File(fullPath)
                    val name = file.name
                    val lower = name.lowercase()
                    if (lower.endsWith(".part")) continue
                    if (lower.endsWith(".thorbak") || lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apk")) {
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(fullPath), null, null)
                        val uri = file.toUri()
                        val key = "${lower}:$size"
                        if (!seenUris.add(uri.toString()) || !seenKeys.add(key)) continue
                        val id = (fullPath.hashCode().toLong() shl 32) xor (size xor (mtime shl 16))
                        parseArchiveItem(id, uri, name, size, mtime)?.let {
                            results.add(it)
                        }
                    }
                }
            }
        }
        return results
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryMediaStoreDownloads(
        seenUris: MutableSet<String>,
        seenKeys: MutableSet<String>,
    ): List<BackupArchiveItem> {
        val results = mutableListOf<BackupArchiveItem>()
        val contentUris = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external"),
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )

        val selection = "(${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND (${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
        val selectionArgs = arrayOf(
            "%/Download/Thor/%",
            "%/Downloads/Thor/%",
            "%Download/Thor%",
            "%Downloads/Thor%",
            "%.thorbak",
            "%.xapk",
            "%.apks",
            "%.apk",
        )

        for (contentUri in contentUris) {
            try {
                context.contentResolver.query(
                    contentUri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        if (name.endsWith(".part")) continue

                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol)
                        val uri = ContentUris.withAppendedId(contentUri, id)
                        val key = "${name.lowercase()}:$size"
                        if (!seenUris.add(uri.toString()) || !seenKeys.add(key)) continue

                        parseArchiveItem(id, uri, name, size, date)?.let { results.add(it) }
                    }
                }
            } catch (_: Exception) {
                // Ignore individual table query failures
            }
        }
        return results
    }

    private fun queryFilesystemDownloads(
        seenUris: MutableSet<String>,
        seenKeys: MutableSet<String>,
    ): List<BackupArchiveItem> {
        val results = mutableListOf<BackupArchiveItem>()
        val candidateDirs = listOfNotNull(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                THOR_DOWNLOADS_SUBDIR,
            ),
            File(Environment.getExternalStorageDirectory(), "Download/$THOR_DOWNLOADS_SUBDIR"),
            File(Environment.getExternalStorageDirectory(), "Downloads/$THOR_DOWNLOADS_SUBDIR"),
        ).distinctBy { it.absolutePath }

        for (dir in candidateDirs) {
            try {
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles { f ->
                    val n = f.name.lowercase()
                    !n.endsWith(".part") && (n.endsWith(".thorbak") || n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apk"))
                } ?: continue

                for (f in files) {
                    val uri = f.toUri()
                    val key = "${f.name.lowercase()}:${f.length()}"
                    if (!seenUris.add(uri.toString()) || !seenKeys.add(key)) continue
                    val id = (f.absolutePath.hashCode().toLong() shl 32) xor (f.length() xor (f.lastModified()))
                    parseArchiveItem(id, uri, f.name, f.length(), f.lastModified() / 1000)
                        ?.let { results.add(it) }
                }
            } catch (_: Exception) {
                // Ignore filesystem scan errors
            }
        }
        return results
    }

    private fun queryCustomTree(
        treeUriString: String,
        seenUris: MutableSet<String>,
        seenKeys: MutableSet<String>,
    ): List<BackupArchiveItem> {
        val results = mutableListOf<BackupArchiveItem>()
        try {
            val root = DocumentFile.fromTreeUri(context, treeUriString.toUri()) ?: return results
            if (!root.exists() || !root.isDirectory) return results

            for (doc in root.listFiles()) {
                val name = doc.name ?: continue
                val lower = name.lowercase()
                if (lower.endsWith(".part")) continue
                if (lower.endsWith(".thorbak") || lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apk")) {
                    val key = "${name.lowercase()}:${doc.length()}"
                    if (!seenUris.add(doc.uri.toString()) || !seenKeys.add(key)) continue
                    parseArchiveItem(
                        doc.uri.hashCode().toLong(),
                        doc.uri,
                        name,
                        doc.length(),
                        doc.lastModified() / 1000,
                    )?.let { results.add(it) }
                }
            }
        } catch (_: Exception) {
            // Ignored
        }
        return results
    }

    private fun parseArchiveItem(
        id: Long,
        uri: Uri,
        displayName: String,
        sizeBytes: Long,
        dateModifiedEpochSec: Long,
    ): BackupArchiveItem? {
        val lower = displayName.lowercase()
        val extension = when {
            lower.endsWith(".thorbak") -> "thorbak"
            lower.endsWith(".xapk") -> "xapk"
            lower.endsWith(".apks") -> "apks"
            lower.endsWith(".apk") -> "apk"
            else -> return null
        }

        val kind = if (extension == "thorbak") {
            BackupArchiveKind.DATA_BACKUP
        } else {
            BackupArchiveKind.APP_BUNDLE
        }

        // Package name extraction heuristic: "com.example.app-100.thorbak" -> "com.example.app"
        val nameWithoutExt = displayName.substringBeforeLast('.')
        val possiblePkg = nameWithoutExt.substringBefore('-')
        val packageName = if (possiblePkg.contains('.') && possiblePkg.length > 3) possiblePkg else null

        val appName = if (!packageName.isNullOrBlank()) {
            try {
                val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags))
                } else {
                    context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                }
                context.packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                null
            }
        } else {
            val cleaned = nameWithoutExt.replace(Regex("_[0-9]+(\\.[0-9]+)*.*$"), "").replace('_', ' ').trim()
            cleaned.ifBlank { null }
        }

        return BackupArchiveItem(
            id = id,
            uriString = uri.toString(),
            displayName = displayName,
            appName = appName,
            packageName = packageName,
            sizeBytes = sizeBytes,
            dateModifiedEpochSec = dateModifiedEpochSec,
            kind = kind,
            extension = extension,
        )
    }
}
