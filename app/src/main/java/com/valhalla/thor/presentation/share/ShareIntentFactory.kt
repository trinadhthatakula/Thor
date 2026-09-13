// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.repository.AppBundleFileStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

/** Rebuilds a share Intent only from the current durable task and the complete private ready set. */
@Single
class ShareIntentFactory(
    context: Context,
    dataTaskDao: DataTaskDao,
    private val fileStore: AppBundleFileStore,
) {
    private val cacheDirectory = context.cacheDir
    private val dataTaskStore = DataTaskStore(dataTaskDao)

    /** The foreground handoff holds ReadyShareAccessLock through validation and chooser dispatch. */
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

    private fun createValidatedIntent(taskId: UUID, snapshot: DataTaskSnapshot, nowMs: Long): Intent? {
        if (snapshot.taskId != taskId || snapshot.kind != DataTaskKind.SHARE_PREPARE ||
            snapshot.payloadSchemaVersion != 1) return null
        if (snapshot.state != DataTaskState.READY && snapshot.state != DataTaskState.READY_PARTIAL) return null
        val detail = snapshot.detail as? StoredDataTaskDetail.SharePrepare ?: return null
        if (detail.publicationPolicy != DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY ||
            detail.deterministicStagingIdentity != "stage-$taskId") return null
        if (snapshot.outputs.isEmpty() || snapshot.items.map { it.ordinal } != snapshot.items.indices.toList()) return null
        val succeeded = snapshot.items.filter { it.state == DataTaskItemState.SUCCEEDED }
        if (succeeded.map { it.ordinal } != snapshot.outputs.map { it.itemOrdinal } ||
            snapshot.outputs.map { it.outputId }.distinct().size != snapshot.outputs.size) return null

        val files = snapshot.outputs.zip(succeeded).map { (output, item) ->
            if (output.state != DataTaskOutputState.READY || output.expiresAtEpochMs?.let { it > nowMs } != true ||
                output.byteSize < 0 || !detail.requestedFormat.accepts(output.displayName, output.mimeType)) return null
            val file = readyShareOwnedFile(cacheDirectory, taskId, item, output) ?: return null
            val path = file.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path) ||
                !file.canRead() || Files.size(path) != output.byteSize) return null
            file
        }
        // Only generate URIs after every row and leaf has passed validation.
        val uris = files.map { file -> fileStore.shareUri(file).toUri().takeIf(::isOwnProviderUri) ?: return null }
        val mimes = snapshot.outputs.map { it.mimeType }.distinct()
        return shareIntent(uris, mimes, snapshot.outputs.first().displayName)
    }

    companion object {
        fun createSingleShare(uri: Uri, mimeType: String): Intent? {
            if (!isOwnProviderUri(uri) || mimeType !in BundleFormat.entries.map { it.mime }) return null
            return shareIntent(listOf(uri), listOf(mimeType), uri.lastPathSegment.orEmpty())
        }

        private fun isOwnProviderUri(uri: Uri): Boolean =
            uri.scheme == "content" && uri.authority == "${BuildConfig.APPLICATION_ID}.provider"

        internal fun shareIntent(uris: List<Uri>, mimes: List<String>, label: String): Intent =
            Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimes.singleOrNull() ?: "*/*"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = ClipData(ClipDescription(label, mimes.toTypedArray()), ClipData.Item(uris.first())).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
    }
}
