// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.DataTaskItemSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskOutputSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.repository.AppBundleFileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

private const val SHARE_READY_DIRECTORY = "share_ready"

/** Rebuilds a share Intent only from the current durable task and private ready files. */
@Single
class ShareIntentFactory(
    context: Context,
    dataTaskDao: DataTaskDao,
    private val fileStore: AppBundleFileStore,
) {
    private val cacheDirectory = context.cacheDir
    private val dataTaskStore = DataTaskStore(dataTaskDao)
    private val providerAuthority = "${BuildConfig.APPLICATION_ID}.provider"

    /** Returns null for every stale, malformed, incomplete, or inaccessible handoff. */
    suspend fun create(taskId: UUID, nowMs: Long): Intent? {
        if (nowMs < 0) return null
        return try {
            val snapshot = dataTaskStore.loadTask(taskId) ?: return null
            createValidatedIntent(taskId, snapshot, nowMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun createValidatedIntent(
        taskId: UUID,
        snapshot: DataTaskSnapshot,
        nowMs: Long,
    ): Intent? {
        if (snapshot.taskId != taskId || snapshot.kind != DataTaskKind.SHARE_PREPARE) return null
        if (snapshot.state != DataTaskState.READY && snapshot.state != DataTaskState.READY_PARTIAL) {
            return null
        }
        val detail = snapshot.detail as? StoredDataTaskDetail.SharePrepare ?: return null
        if (
            detail.publicationPolicy !=
            DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY
        ) {
            return null
        }
        if (snapshot.outputs.isEmpty()) return null
        if (snapshot.items.map(DataTaskItemSnapshot::ordinal) != snapshot.items.indices.toList()) {
            return null
        }
        val itemsByOrdinal = snapshot.items.associateBy(DataTaskItemSnapshot::ordinal)
        if (itemsByOrdinal.size != snapshot.items.size) return null
        if (snapshot.outputs.map(DataTaskOutputSnapshot::itemOrdinal)
                .distinct().size != snapshot.outputs.size
        ) {
            return null
        }

        val cacheRoot = cacheDirectory.canonicalFile
        val files = snapshot.outputs.map { output ->
            val item = itemsByOrdinal[output.itemOrdinal] ?: return null
            validateOutput(
                taskId = taskId,
                item = item,
                output = output,
                expectedMime = detail.requestedFormat.mime,
                nowMs = nowMs,
                cacheRoot = cacheRoot,
            ) ?: return null
        }

        // URI creation happens only after every Room row and every file has passed validation.
        val uris = files.map { file ->
            fileStore.shareUri(file).toUri().takeIf { uri ->
                uri.scheme == "content" && uri.authority == providerAuthority
            } ?: return null
        }
        val clipData = ClipData(
            ClipDescription(
                snapshot.outputs.first().displayName,
                arrayOf(detail.requestedFormat.mime),
            ),
            ClipData.Item(uris.first()),
        ).apply {
            uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
        }

        return Intent(
            if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        ).apply {
            type = detail.requestedFormat.mime
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            this.clipData = clipData
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
    }

    private fun validateOutput(
        taskId: UUID,
        item: DataTaskItemSnapshot,
        output: DataTaskOutputSnapshot,
        expectedMime: String,
        nowMs: Long,
        cacheRoot: File,
    ): File? {
        if (item.state != DataTaskItemState.SUCCEEDED) return null
        if (item.ordinal != output.itemOrdinal) return null
        val expectedIdentity = "item-$taskId-${item.ordinal}"
        if (item.deterministicStagingIdentity != expectedIdentity) return null
        if (output.state != DataTaskOutputState.READY) return null
        if (output.expiresAtEpochMs?.let { it > nowMs } != true) return null
        if (output.mimeType != expectedMime || output.byteSize < 0) return null
        if (!output.displayName.isSafePathComponent()) return null
        if (!item.packageName.isSafePathComponent()) return null

        val expectedRelativePath = "$SHARE_READY_DIRECTORY/$expectedIdentity/" +
                "${item.packageName}/${output.displayName}"
        if (output.privateRelativePath != expectedRelativePath) return null

        val file = File(cacheRoot, expectedRelativePath)
        val pathComponents = listOf(
            SHARE_READY_DIRECTORY,
            expectedIdentity,
            item.packageName,
            output.displayName,
        )
        var current = cacheRoot
        pathComponents.forEach { component ->
            current = File(current, component)
            if (Files.isSymbolicLink(current.toPath())) return null
        }
        val normalizedPath = file.absoluteFile.toPath().normalize()
        val canonicalPath = file.canonicalFile.toPath()
        if (canonicalPath != normalizedPath || !canonicalPath.startsWith(cacheRoot.toPath())) {
            return null
        }
        if (!Files.isRegularFile(canonicalPath, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isReadable(canonicalPath) || !file.canRead()) return null
        if (Files.size(canonicalPath) != output.byteSize) return null
        return file
    }

    private fun String.isSafePathComponent(): Boolean =
        isNotBlank() && this != "." && this != ".." &&
                none { it == '/' || it == '\\' || it.isISOControl() }
}
