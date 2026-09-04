// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.core.net.toUri
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.Single

internal sealed interface RestoreSourceResolution {
    data class Ready(val uriString: String) : RestoreSourceResolution
    data object WaitingForSource : RestoreSourceResolution
    data object OwnershipLost : RestoreSourceResolution
}

internal interface RestoreSourceStagingDependencies {
    fun takeSource(taskId: UUID): String?
    suspend fun copyToPrivate(taskId: UUID, rawUri: String): String?
    suspend fun commitPrivateSource(
        claim: DataSyncClaim,
        privateRelativePath: String,
        nowMs: Long,
    ): Boolean

    fun privateSourceUri(privateRelativePath: String): String?
    fun persistedSourceUri(grantIdentity: String): String?
    fun discardPrivateSource(privateRelativePath: String)
    fun nowMs(): Long
}

@Single
internal class AndroidRestoreSourceStagingDependencies(
    context: Context,
    private val sources: RestoreSourceGrantHolder,
    dao: DataTaskDao,
) : RestoreSourceStagingDependencies {
    private val applicationContext = context.applicationContext
    private val filesRoot = applicationContext.filesDir.canonicalFile
    private val store = DataTaskStore(dao)

    override fun takeSource(taskId: UUID): String? = sources.takeForTask(taskId)?.toString()

    override suspend fun copyToPrivate(taskId: UUID, rawUri: String): String? {
        val relativePath = "data_tasks/$taskId/restore-source.thor"
        val destination = resolvePrivateFile(relativePath) ?: return null
        val parent = destination.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) return null
        val partial = File(parent, "${destination.name}.part")
        partial.delete()
        var moved = false
        try {
            val input = runCatching {
                applicationContext.contentResolver.openInputStream(rawUri.toUri())
            }.getOrNull() ?: return null
            input.use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            moveCommitted(partial, destination)
            moved = true
            return relativePath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            if (!moved) partial.delete()
        }
    }

    override suspend fun commitPrivateSource(
        claim: DataSyncClaim,
        privateRelativePath: String,
        nowMs: Long,
    ): Boolean {
        val item = requireNotNull(claim.item)
        return store.commitPrivateRestoreSource(
            taskId = claim.taskId,
            taskClaimToken = claim.claimToken,
            itemOrdinal = item.ordinal,
            itemClaimToken = item.claimToken,
            privateRelativePath = privateRelativePath,
            nowMs = nowMs,
        )
    }

    override fun privateSourceUri(privateRelativePath: String): String? =
        resolvePrivateFile(privateRelativePath)
            ?.takeIf(File::isFile)
            ?.toUri()
            ?.toString()

    override fun persistedSourceUri(grantIdentity: String): String? =
        applicationContext.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .firstOrNull { it.toOpaqueGrantIdentity() == grantIdentity }

    override fun discardPrivateSource(privateRelativePath: String) {
        val file = resolvePrivateFile(privateRelativePath) ?: return
        file.delete()
        file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    private fun resolvePrivateFile(relativePath: String): File? {
        val candidate = File(filesRoot, relativePath).canonicalFile
        return candidate.takeIf { it.path.startsWith(filesRoot.path + File.separator) }
    }

    private fun moveCommitted(partial: File, destination: File) {
        try {
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun String.toOpaqueGrantIdentity(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
        return "tree_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

@Single
internal class RestoreSourceStager internal constructor(
    private val dependencies: RestoreSourceStagingDependencies,
) {
    internal constructor(
        takeSource: (UUID) -> String?,
        copyToPrivate: suspend (UUID, String) -> String?,
        commitPrivateSource: suspend (DataSyncClaim, String, Long) -> Boolean,
        privateSourceUri: (String) -> String?,
        persistedSourceUri: (String) -> String? = { null },
        discardPrivateSource: (String) -> Unit,
        nowMs: () -> Long,
    ) : this(
        object : RestoreSourceStagingDependencies {
            override fun takeSource(taskId: UUID): String? = takeSource(taskId)
            override suspend fun copyToPrivate(taskId: UUID, rawUri: String): String? =
                copyToPrivate(taskId, rawUri)

            override suspend fun commitPrivateSource(
                claim: DataSyncClaim,
                privateRelativePath: String,
                nowMs: Long,
            ): Boolean = commitPrivateSource(claim, privateRelativePath, nowMs)

            override fun privateSourceUri(privateRelativePath: String): String? =
                privateSourceUri(privateRelativePath)

            override fun persistedSourceUri(grantIdentity: String): String? =
                persistedSourceUri(grantIdentity)

            override fun discardPrivateSource(privateRelativePath: String) =
                discardPrivateSource(privateRelativePath)

            override fun nowMs(): Long = nowMs()
        }
    )

    suspend fun resolve(
        claim: DataSyncClaim,
        source: StoredRestoreSource,
    ): RestoreSourceResolution = when (source) {
        StoredRestoreSource.AwaitingTransientGrant -> stageTransientSource(claim)
        is StoredRestoreSource.PersistedGrant -> dependencies.persistedSourceUri(source.grantIdentity)
            ?.let(RestoreSourceResolution::Ready)
            ?: RestoreSourceResolution.WaitingForSource

        is StoredRestoreSource.PrivateCopy -> dependencies.privateSourceUri(source.privateRelativePath)
            ?.let(RestoreSourceResolution::Ready)
            ?: RestoreSourceResolution.WaitingForSource
    }

    fun discard(source: StoredRestoreSource) {
        if (source is StoredRestoreSource.PrivateCopy) {
            dependencies.discardPrivateSource(source.privateRelativePath)
        }
    }

    private suspend fun stageTransientSource(claim: DataSyncClaim): RestoreSourceResolution {
        val rawUri = dependencies.takeSource(claim.taskId)
            ?: return RestoreSourceResolution.WaitingForSource
        val privatePath = dependencies.copyToPrivate(claim.taskId, rawUri)
            ?: return RestoreSourceResolution.WaitingForSource
        if (!dependencies.commitPrivateSource(claim, privatePath, dependencies.nowMs())) {
            dependencies.discardPrivateSource(privatePath)
            return RestoreSourceResolution.OwnershipLost
        }
        val privateUri = dependencies.privateSourceUri(privatePath)
            ?: return RestoreSourceResolution.WaitingForSource
        return RestoreSourceResolution.Ready(privateUri)
    }
}
