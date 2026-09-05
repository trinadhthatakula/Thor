// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.core.net.toUri
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.StoredRestoreSource
import com.valhalla.thor.domain.model.isPrivateRestoreSourceRelativePath
import com.valhalla.thor.domain.model.privateRestoreSourceRelativePath
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal sealed interface RestoreSourceResolution {
    data class Ready(val uriString: String) : RestoreSourceResolution
    data object WaitingForSource : RestoreSourceResolution
    data object OwnershipLost : RestoreSourceResolution
}

internal sealed interface RestoreSourceCopyResult {
    data class Completed(val privateRelativePath: String) : RestoreSourceCopyResult
    data object SourceUnavailable : RestoreSourceCopyResult
    data object OwnershipLost : RestoreSourceCopyResult
}

internal interface RestoreSourceStagingDependencies {
    fun takeSource(taskId: UUID, capabilityToken: String): String?
    suspend fun copyToPrivate(
        taskId: UUID,
        rawUri: String,
        reportProgress: suspend (copiedBytes: Long) -> Boolean,
    ): RestoreSourceCopyResult

    suspend fun commitPrivateSource(
        claim: DataSyncClaim,
        privateRelativePath: String,
        nowMs: Long,
    ): Boolean

    suspend fun readStoredSource(taskId: UUID): StoredRestoreSource?

    fun privateSourceUri(taskId: UUID, privateRelativePath: String): String?
    fun persistedSourceUri(grantIdentity: String): String?
    fun discardPrivateSource(taskId: UUID, privateRelativePath: String)
    fun discardUncommittedTaskSources(taskId: UUID)
    fun nowMs(): Long
}

@Single
internal class AndroidRestoreSourceStagingDependencies(
    context: Context,
    private val sources: RestoreSourceGrantHolder,
    dao: DataTaskDao,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : RestoreSourceStagingDependencies {
    private val applicationContext = context.applicationContext
    private val filesRoot = applicationContext.filesDir.canonicalFile
    private val store = DataTaskStore(dao)

    override fun takeSource(taskId: UUID, capabilityToken: String): String? =
        sources.take(taskId, capabilityToken)

    override suspend fun copyToPrivate(
        taskId: UUID,
        rawUri: String,
        reportProgress: suspend (copiedBytes: Long) -> Boolean,
    ): RestoreSourceCopyResult {
        val relativePath = privateRestoreSourceRelativePath(taskId)
        val destination = resolvePrivateFile(taskId, relativePath)
            ?: return RestoreSourceCopyResult.SourceUnavailable
        val parent = destination.parentFile ?: return RestoreSourceCopyResult.SourceUnavailable
        if (!parent.exists() && !parent.mkdirs()) return RestoreSourceCopyResult.SourceUnavailable
        val partial = File(parent, "${destination.name}.part")
        partial.delete()
        var moved = false
        try {
            val input = runCatching {
                runInterruptible(ioDispatcher) {
                    applicationContext.contentResolver.openInputStream(rawUri.toUri())
                }
            }.getOrNull() ?: return RestoreSourceCopyResult.SourceUnavailable
            input.use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copiedBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = runInterruptible(ioDispatcher) { source.read(buffer) }
                        if (count < 0) break
                        runInterruptible(ioDispatcher) { output.write(buffer, 0, count) }
                        copiedBytes += count
                        if (!reportProgress(copiedBytes)) {
                            return RestoreSourceCopyResult.OwnershipLost
                        }
                    }
                    runInterruptible(ioDispatcher) { output.fd.sync() }
                }
            }
            moveCommitted(partial, destination)
            moved = true
            return RestoreSourceCopyResult.Completed(relativePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RestoreSourceCopyResult.SourceUnavailable
        } finally {
            if (!moved) partial.delete()
        }
    }

    override suspend fun commitPrivateSource(
        claim: DataSyncClaim,
        privateRelativePath: String,
        nowMs: Long,
    ): Boolean {
        if (!isPrivateRestoreSourceRelativePath(claim.taskId, privateRelativePath)) return false
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

    override suspend fun readStoredSource(taskId: UUID): StoredRestoreSource? =
        store.loadRestoreSource(taskId)

    override fun privateSourceUri(taskId: UUID, privateRelativePath: String): String? =
        resolvePrivateFile(taskId, privateRelativePath)
            ?.takeIf(File::isFile)
            ?.toUri()
            ?.toString()

    override fun persistedSourceUri(grantIdentity: String): String? =
        applicationContext.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .firstOrNull { it.toOpaqueGrantIdentity() == grantIdentity }

    override fun discardPrivateSource(taskId: UUID, privateRelativePath: String) {
        val file = resolvePrivateFile(taskId, privateRelativePath) ?: return
        file.delete()
        file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    override fun discardUncommittedTaskSources(taskId: UUID) {
        val final = resolvePrivateFile(taskId, privateRestoreSourceRelativePath(taskId)) ?: return
        File(final.parentFile, "${final.name}.part").delete()
        final.delete()
        final.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    private fun resolvePrivateFile(taskId: UUID, relativePath: String): File? {
        if (!isPrivateRestoreSourceRelativePath(taskId, relativePath)) return null
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
        takeSource: (UUID, String) -> String?,
        copyToPrivate: suspend (
            UUID,
            String,
            suspend (copiedBytes: Long) -> Boolean,
        ) -> RestoreSourceCopyResult,
        commitPrivateSource: suspend (DataSyncClaim, String, Long) -> Boolean,
        readStoredSource: suspend (UUID) -> StoredRestoreSource? = { null },
        privateSourceUri: (UUID, String) -> String?,
        persistedSourceUri: (String) -> String? = { null },
        discardPrivateSource: (UUID, String) -> Unit,
        discardUncommittedTaskSources: (UUID) -> Unit,
        nowMs: () -> Long,
    ) : this(
        object : RestoreSourceStagingDependencies {
            override fun takeSource(taskId: UUID, capabilityToken: String): String? =
                takeSource(taskId, capabilityToken)

            override suspend fun copyToPrivate(
                taskId: UUID,
                rawUri: String,
                reportProgress: suspend (copiedBytes: Long) -> Boolean,
            ): RestoreSourceCopyResult = copyToPrivate(taskId, rawUri, reportProgress)

            override suspend fun commitPrivateSource(
                claim: DataSyncClaim,
                privateRelativePath: String,
                nowMs: Long,
            ): Boolean = commitPrivateSource(claim, privateRelativePath, nowMs)

            override suspend fun readStoredSource(taskId: UUID): StoredRestoreSource? =
                readStoredSource(taskId)

            override fun privateSourceUri(taskId: UUID, privateRelativePath: String): String? =
                privateSourceUri(taskId, privateRelativePath)

            override fun persistedSourceUri(grantIdentity: String): String? =
                persistedSourceUri(grantIdentity)

            override fun discardPrivateSource(taskId: UUID, privateRelativePath: String) =
                discardPrivateSource(taskId, privateRelativePath)

            override fun discardUncommittedTaskSources(taskId: UUID) =
                discardUncommittedTaskSources(taskId)

            override fun nowMs(): Long = nowMs()
        }
    )

    suspend fun resolve(
        claim: DataSyncClaim,
        source: StoredRestoreSource,
        checkpoints: DataTaskCheckpointSink,
    ): RestoreSourceResolution = when (source) {
        StoredRestoreSource.AwaitingTransientGrant -> stageTransientSource(claim, checkpoints)
        is StoredRestoreSource.PersistedGrant -> dependencies.persistedSourceUri(source.grantIdentity)
            ?.let(RestoreSourceResolution::Ready)
            ?: RestoreSourceResolution.WaitingForSource

        is StoredRestoreSource.PrivateCopy -> if (
            isPrivateRestoreSourceRelativePath(claim.taskId, source.privateRelativePath)
        ) {
            dependencies.privateSourceUri(claim.taskId, source.privateRelativePath)
                ?.let(RestoreSourceResolution::Ready)
                ?: RestoreSourceResolution.WaitingForSource
        } else {
            RestoreSourceResolution.WaitingForSource
        }
    }

    fun discardUncommittedTaskSources(taskId: UUID) {
        dependencies.discardUncommittedTaskSources(taskId)
    }

    fun discard(taskId: UUID, source: StoredRestoreSource) {
        if (
            source is StoredRestoreSource.PrivateCopy &&
            isPrivateRestoreSourceRelativePath(taskId, source.privateRelativePath)
        ) {
            dependencies.discardPrivateSource(taskId, source.privateRelativePath)
        }
    }

    private suspend fun stageTransientSource(
        claim: DataSyncClaim,
        checkpoints: DataTaskCheckpointSink,
    ): RestoreSourceResolution {
        val rawUri = claim.transientSourceToken
            ?.let { token -> dependencies.takeSource(claim.taskId, token) }
        if (rawUri == null) {
            dependencies.discardUncommittedTaskSources(claim.taskId)
            return RestoreSourceResolution.WaitingForSource
        }
        val item = requireNotNull(claim.item)
        val copy = try {
            withTimeout(RESTORE_STAGING_TIMEOUT) {
                dependencies.copyToPrivate(claim.taskId, rawUri) { copiedBytes ->
                    checkpoints.persist(
                        DataTaskCheckpoint(
                            stage = DataTaskStage.STAGING_SOURCE,
                            completed = copiedBytes,
                            total = 0L,
                            activeItemOrdinal = item.ordinal,
                            activeItemLabel = item.displayLabel,
                            destructiveStarted = false,
                            restoreMutationBreadcrumb = null,
                            recordedAtEpochMs = dependencies.nowMs(),
                        )
                    ) == DataTaskSinkWrite.APPLIED
                }
            }
        } catch (_: TimeoutCancellationException) {
            discardUncommitted(claim.taskId)
            return RestoreSourceResolution.WaitingForSource
        } catch (cancelled: CancellationException) {
            discardUncommitted(claim.taskId)
            throw cancelled
        }
        val privatePath = when (copy) {
            is RestoreSourceCopyResult.Completed -> copy.privateRelativePath
            RestoreSourceCopyResult.SourceUnavailable -> {
                return RestoreSourceResolution.WaitingForSource
            }

            RestoreSourceCopyResult.OwnershipLost -> return RestoreSourceResolution.OwnershipLost
        }
        if (!isPrivateRestoreSourceRelativePath(claim.taskId, privatePath)) {
            dependencies.discardUncommittedTaskSources(claim.taskId)
            return RestoreSourceResolution.OwnershipLost
        }
        val callerJob = currentCoroutineContext()[Job]
        val committed = withContext(NonCancellable) {
            val write = runCatching {
                withTimeout(RESTORE_COMMIT_TIMEOUT) {
                    dependencies.commitPrivateSource(claim, privatePath, dependencies.nowMs())
                }
            }
            when {
                write.getOrNull() == true -> true
                write.isSuccess -> {
                    dependencies.discardUncommittedTaskSources(claim.taskId)
                    false
                }

                else -> reconcileAmbiguousCommit(claim.taskId, privatePath)
            }
        }
        callerJob?.ensureActive()
        if (!committed) {
            return RestoreSourceResolution.OwnershipLost
        }
        val privateUri = dependencies.privateSourceUri(claim.taskId, privatePath)
            ?: return RestoreSourceResolution.WaitingForSource
        return RestoreSourceResolution.Ready(privateUri)
    }

    private suspend fun reconcileAmbiguousCommit(taskId: UUID, privatePath: String): Boolean {
        val storedSource = runCatching {
            withTimeout(RESTORE_COMMIT_TIMEOUT) {
                dependencies.readStoredSource(taskId)
            }
        }
        if (storedSource.isFailure) return false
        if (storedSource.getOrNull() == StoredRestoreSource.PrivateCopy(privatePath)) return true
        dependencies.discardUncommittedTaskSources(taskId)
        return false
    }

    private suspend fun discardUncommitted(taskId: UUID) {
        withContext(NonCancellable) {
            dependencies.discardUncommittedTaskSources(taskId)
        }
    }

    private companion object {
        val RESTORE_STAGING_TIMEOUT = 9.minutes
        val RESTORE_COMMIT_TIMEOUT = 2.seconds
    }
}
