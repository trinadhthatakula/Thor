// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import android.content.Context
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

internal interface ReadyShareRetentionDependencies {
    val cacheDirectory: File
    val accessLock: ReadyShareAccessLock
    val ioDispatcher: CoroutineDispatcher
    fun nowMs(): Long
    fun deleteFile(file: File): Boolean
    suspend fun expiredReadyShareTasks(nowMs: Long): List<DataTaskSnapshot>
    suspend fun readyShareRetentionSnapshot(taskId: UUID, nowMs: Long): DataTaskSnapshot?
    suspend fun markReadyTaskExpiredAfterCleanup(
        expected: DataTaskSnapshot,
        outputIds: List<UUID>,
        nowMs: Long,
    ): Boolean
}

@Single
internal class RoomReadyShareRetentionDependencies(
    context: Context,
    dao: DataTaskDao,
    override val accessLock: ReadyShareAccessLock,
    @Named("io") override val ioDispatcher: CoroutineDispatcher,
) : ReadyShareRetentionDependencies {
    override val cacheDirectory: File = context.cacheDir
    private val store = DataTaskStore(dao)
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun deleteFile(file: File): Boolean = file.delete()
    override suspend fun expiredReadyShareTasks(nowMs: Long) = store.expiredReadyShareTasks(nowMs)
    override suspend fun readyShareRetentionSnapshot(taskId: UUID, nowMs: Long) =
        store.readyShareRetentionSnapshot(taskId, nowMs)
    override suspend fun markReadyTaskExpiredAfterCleanup(
        expected: DataTaskSnapshot,
        outputIds: List<UUID>,
        nowMs: Long,
    ) = store.markReadyTaskExpiredAfterCleanup(expected, outputIds, nowMs)
}

/** Deletes only due, operation-owned leaves; Room settlement follows successful cleanup of the due set. */
@Single(binds = [ReadyShareRetentionCleanup::class])
class ReadyShareRetentionSweeper internal constructor(
    private val dependencies: ReadyShareRetentionDependencies,
) : ReadyShareRetentionCleanup {
    override suspend fun sweep() = withContext(dependencies.ioDispatcher) {
        val candidates = dependencies.expiredReadyShareTasks(dependencies.nowMs())
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                dependencies.accessLock.withLock {
                    val now = dependencies.nowMs()
                    val fresh = dependencies.readyShareRetentionSnapshot(candidate.taskId, now)
                    if (fresh != candidate || !validOwner(candidate)) return@withLock
                    val due = candidate.outputs.filter {
                        it.state == DataTaskOutputState.READY && it.expiresAtEpochMs?.let { time -> time <= now } == true
                    }
                    if (due.isEmpty()) return@withLock
                    // Validate the entire due set before touching a single leaf.
                    val files = due.map { output ->
                        val item = candidate.items.singleOrNull { it.ordinal == output.itemOrdinal }
                            ?: return@withLock
                        readyShareOwnedFile(dependencies.cacheDirectory, candidate.taskId, item, output)
                            ?.takeIf { file ->
                                Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                                    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                            } ?: return@withLock
                        // Keep the dependency's path spelling (e.g. /var versus /private/var).
                        // Ownership above is still proved against the canonical cache root.
                        File(dependencies.cacheDirectory, requireNotNull(output.privateRelativePath))
                    }
                    for (file in files) {
                        currentCoroutineContext().ensureActive()
                        if (!Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                            !dependencies.deleteFile(file)) return@withLock
                        if (!Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withLock
                    }
                    dependencies.markReadyTaskExpiredAfterCleanup(candidate, due.map { it.outputId }, now)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Deletion/CAS failure leaves the exact rows for retry, including already-missing leaves.
            }
        }
    }

    private fun validOwner(task: DataTaskSnapshot): Boolean {
        val detail = task.detail as? StoredDataTaskDetail.SharePrepare ?: return false
        return task.kind == DataTaskKind.SHARE_PREPARE && task.payloadSchemaVersion == 1 &&
            task.state in setOf(DataTaskState.READY, DataTaskState.READY_PARTIAL, DataTaskState.EXPIRED) &&
            detail.publicationPolicy == DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY &&
            detail.deterministicStagingIdentity == "stage-${task.taskId}" &&
            task.items.map { it.ordinal } == task.items.indices.toList() &&
            task.outputs.map { it.outputId }.distinct().size == task.outputs.size &&
            task.outputs.map { it.itemOrdinal }.distinct().size == task.outputs.size &&
            task.outputs.all { output -> task.items.singleOrNull { it.ordinal == output.itemOrdinal }
                ?.state == DataTaskItemState.SUCCEEDED }
    }
}
