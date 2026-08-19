// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.valhalla.thor.domain.repository.BackupArchiveItem
import com.valhalla.thor.domain.repository.BackupArchiveKind
import com.valhalla.thor.domain.repository.BackupArchiveScanner
import com.valhalla.thor.domain.repository.PreferenceRepository
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
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : BackupArchiveScanner {

    override fun scanBackups(): Flow<List<BackupArchiveItem>> = flow {
        val items = mutableListOf<BackupArchiveItem>()
        val seenNames = mutableSetOf<String>()

        // 1. Scan default Downloads/Thor directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            items.addAll(queryMediaStoreDownloads(seenNames))
        } else {
            items.addAll(queryLegacyDownloads(seenNames))
        }

        // 2. Scan custom SAF folder if user configured one
        val customTreeUri = preferences.userPreferences.first().exportDirUri
        if (!customTreeUri.isNullOrBlank()) {
            items.addAll(queryCustomTree(customTreeUri, seenNames))
        }

        // Sort descending by date modified
        items.sortByDescending { it.dateModifiedEpochSec }
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
                    val file = File(path)
                    file.exists() && file.delete()
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryMediaStoreDownloads(seenNames: MutableSet<String>): List<BackupArchiveItem> {
        val results = mutableListOf<BackupArchiveItem>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.RELATIVE_PATH,
        )

        val selection = "(${MediaStore.Downloads.RELATIVE_PATH} LIKE ? OR ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?) AND (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?)"
        val selectionArgs = arrayOf(
            "%Download/Thor%",
            "%Downloads/Thor%",
            "%.thorbak",
            "%.xapk",
            "%.apks",
            "%.apk",
        )

        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (name.endsWith(".part") || !seenNames.add(name)) continue

                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

                    parseArchiveItem(id, uri, name, size, date)?.let { results.add(it) }
                }
            }
        } catch (_: Exception) {
            // Permission or resolver failure gracefully produces whatever else resolved
        }
        return results
    }

    private fun queryLegacyDownloads(seenNames: MutableSet<String>): List<BackupArchiveItem> {
        val results = mutableListOf<BackupArchiveItem>()
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            THOR_DOWNLOADS_SUBDIR,
        )
        if (!dir.exists() || !dir.isDirectory) return results

        val files = dir.listFiles { f ->
            val n = f.name.lowercase()
            !n.endsWith(".part") && (n.endsWith(".thorbak") || n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apk"))
        } ?: return results

        for (f in files) {
            if (!seenNames.add(f.name)) continue
            val uri = f.toUri()
            parseArchiveItem(f.hashCode().toLong(), uri, f.name, f.length(), f.lastModified() / 1000)
                ?.let { results.add(it) }
        }
        return results
    }

    private fun queryCustomTree(
        treeUriString: String,
        seenNames: MutableSet<String>,
    ): List<BackupArchiveItem> {
        val results = mutableListOf<BackupArchiveItem>()
        try {
            val root = DocumentFile.fromTreeUri(context, treeUriString.toUri()) ?: return results
            if (!root.exists() || !root.isDirectory) return results

            for (doc in root.listFiles()) {
                val name = doc.name ?: continue
                val lower = name.lowercase()
                if (lower.endsWith(".part") || !seenNames.add(name)) continue
                if (lower.endsWith(".thorbak") || lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apk")) {
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
        val possiblePkg = nameWithoutExt.substringBefore('-').substringBefore('_')
        val packageName = if (possiblePkg.contains('.') && possiblePkg.length > 3) possiblePkg else null

        return BackupArchiveItem(
            id = id,
            uriString = uri.toString(),
            displayName = displayName,
            packageName = packageName,
            sizeBytes = sizeBytes,
            dateModifiedEpochSec = dateModifiedEpochSec,
            kind = kind,
            extension = extension,
        )
    }
}
