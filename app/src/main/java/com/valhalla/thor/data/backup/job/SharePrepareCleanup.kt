// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.backup.job

import android.content.Context
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.presentation.share.ReadyShareAccessLock
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Incomplete work is never published; final bytes stay until settlement proves they are unused. */
@Single
internal class SharePrepareCleanup(
    context: Context,
    private val dao: DataTaskDao,
    private val access: ReadyShareAccessLock,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {
    private val cache = context.cacheDir.canonicalFile

    /** Called only before releasing LaunchSweepBarrier, never by an independently running sweeper. */
    suspend fun sweepAfterProcessStart() = withContext(ioDispatcher) {
        deleteTree(File(cache, "share_work"))
        for (task in dao.observeRetained().first()) cleanup(task.taskId)
    }

    suspend fun cleanup(taskId: UUID) = withContext(ioDispatcher) {
        access.withLock {
            val task = dao.unownedShareCleanupSnapshot(taskId.toString()) ?: return@withLock
            val unusable = task.state == DataTaskState.CANCELLED || task.state == DataTaskState.FAILED
            val removedOrdinals = mutableSetOf<Int>()
            for (item in task.items) {
                if (item.deterministicStagingIdentity != "item-$taskId-${item.ordinal}" ||
                    !item.packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))) continue
                if (!unusable && task.outputs.any {
                    it.itemOrdinal == item.ordinal && it.state == DataTaskOutputState.READY
                }) continue
                val directory = ownedDirectory("share_ready/${item.deterministicStagingIdentity}/${item.packageName}")
                    ?: continue
                if (deleteTree(directory)) removedOrdinals += item.ordinal
            }
            if (unusable) {
                val removedOutputs = task.outputs.filter {
                    it.itemOrdinal in removedOrdinals && it.state == DataTaskOutputState.READY
                }.map { it.outputId.toString() }
                if (removedOutputs.isNotEmpty()) dao.expireDiscardedShareOutputs(task, removedOutputs)
            }
        }
    }

    private fun ownedDirectory(relative: String): File? {
        var file = cache
        for (component in relative.split('/')) {
            file = File(file, component)
            if (Files.isSymbolicLink(file.toPath())) return null
        }
        return file.takeIf { it.canonicalFile.toPath().startsWith(cache.toPath()) }
    }

    private fun deleteTree(file: File): Boolean {
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(file.toPath())) return false
        if (file.isDirectory && !(file.listFiles()?.all(::deleteTree) ?: false)) return false
        return file.delete()
    }
}
