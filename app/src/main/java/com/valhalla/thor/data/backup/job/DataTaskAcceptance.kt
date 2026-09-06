// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import androidx.core.net.toUri
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.AppShareRequest
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.SecretKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

fun interface DataQueueWakeSignal {
    fun wake(taskId: UUID): ServiceStartResult
}

internal interface DataTaskAcceptanceStore {
    suspend fun insertBackup(
        taskId: UUID,
        request: ArchiveBackupRequest,
        nowMs: Long,
    ): DataTaskState

    suspend fun insertRestore(
        taskId: UUID,
        request: ArchiveRestoreRequest,
        nowMs: Long,
    ): DataTaskState

    suspend fun insertExport(
        taskId: UUID,
        request: AppExportRequest,
        nowMs: Long,
    ): DataTaskState

    suspend fun insertShare(
        taskId: UUID,
        request: AppShareRequest,
        nowMs: Long,
    ): DataTaskState

    suspend fun compareAndSetStartBlocked(
        taskId: UUID,
        expectedState: DataTaskState,
        blockedState: DataTaskState,
        nowMs: Long,
    ): Boolean
}

internal interface DataTaskKeyVault {
    fun put(taskId: UUID, key: SecretKey)
    fun drop(taskId: UUID)
}

internal interface DataTaskRestoreSourceVault {
    fun put(taskId: UUID, uriString: String)

    fun drop(taskId: UUID)
}

internal interface DataTaskAcceptanceDependencies {
    val store: DataTaskAcceptanceStore
    val keyVault: DataTaskKeyVault
    val restoreSourceVault: DataTaskRestoreSourceVault
    val wakeSignal: DataQueueWakeSignal

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey
    fun newTaskId(): UUID
    fun nowMs(): Long
}

@Single
internal class RoomDataTaskAcceptanceDependencies(
    dao: DataTaskDao,
    private val archiveCipher: AppArchiveCipher,
    archiveKeyHolder: ArchiveKeyHolder,
    private val restoreSources: RestoreSourceGrantHolder,
    override val wakeSignal: DataQueueWakeSignal,
) : DataTaskAcceptanceDependencies {
    override val store: DataTaskAcceptanceStore = DataTaskStore(dao)
    override val keyVault: DataTaskKeyVault = object : DataTaskKeyVault {
        override fun put(taskId: UUID, key: SecretKey) {
            archiveKeyHolder.put(taskId.toString(), key)
        }

        override fun drop(taskId: UUID) {
            archiveKeyHolder.drop(taskId.toString())
        }
    }
    override val restoreSourceVault = object : DataTaskRestoreSourceVault {
        override fun put(taskId: UUID, uriString: String) {
            restoreSources.registerAuthorized(taskId, uriString.toUri())
        }

        override fun drop(taskId: UUID) {
            restoreSources.dropTask(taskId)
        }
    }

    override fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): SecretKey = archiveCipher.deriveKey(passphrase, salt, iterations)

    override fun newTaskId(): UUID = UUID.randomUUID()

    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * Accepts data work by durably inserting it before asking Android to wake the executor.
 *
 * This binding becomes active together with the service, manifest component, and concrete wake signal.
 */
@Single
class DataTaskAcceptance internal constructor(
    private val dependencies: DataTaskAcceptanceDependencies,
) {
    private val store = dependencies.store
    private val deriveKey = dependencies::deriveKey
    private val keyVault = dependencies.keyVault
    private val restoreSourceVault = dependencies.restoreSourceVault
    private val wakeSignal = dependencies.wakeSignal

    internal constructor(
        store: DataTaskAcceptanceStore,
        deriveKey: (passphrase: CharArray, salt: ByteArray, iterations: Int) -> SecretKey,
        keyVault: DataTaskKeyVault,
        wakeSignal: DataQueueWakeSignal,
        restoreSourceVault: DataTaskRestoreSourceVault = object : DataTaskRestoreSourceVault {
            override fun put(taskId: UUID, uriString: String) = Unit
            override fun drop(taskId: UUID) = Unit
        },
        taskIdFactory: () -> UUID = UUID::randomUUID,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        dependencies = object : DataTaskAcceptanceDependencies {
            override val store = store
            override val keyVault = keyVault
            override val restoreSourceVault = restoreSourceVault
            override val wakeSignal = wakeSignal
            override fun deriveKey(
                passphrase: CharArray,
                salt: ByteArray,
                iterations: Int,
            ): SecretKey = deriveKey(passphrase, salt, iterations)

            override fun newTaskId(): UUID = taskIdFactory()

            override fun nowMs(): Long = clock()
        },
    )

    suspend fun acceptBackup(
        request: ArchiveBackupRequest,
        passphrase: CharArray,
    ): UUID {
        val taskId = dependencies.newTaskId()
        val key = deriveKey(passphrase, request.salt, KDF_ITERATIONS)
        keyVault.put(taskId, key)
        return acceptPersistedTask(
            taskId = taskId,
            onDefiniteInsertFailure = { keyVault.drop(taskId) },
        ) {
            store.insertBackup(taskId, request, dependencies.nowMs())
        }
    }

    suspend fun acceptRestore(
        request: ArchiveRestoreRequest,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): UUID {
        val taskId = dependencies.newTaskId()
        val key = deriveKey(passphrase, salt, iterations)
        keyVault.put(taskId, key)
        try {
            restoreSourceVault.put(taskId, request.uriString)
        } catch (failure: Exception) {
            keyVault.drop(taskId)
            throw failure
        }
        return acceptPersistedTask(
            taskId = taskId,
            onDefiniteInsertFailure = {
                keyVault.drop(taskId)
                restoreSourceVault.drop(taskId)
            },
        ) {
            store.insertRestore(taskId, request, dependencies.nowMs())
        }
    }

    suspend fun acceptExport(
        request: AppExportRequest,
        onDurablyAccepted: () -> Unit = {},
    ): UUID = acceptExport(
        taskId = dependencies.newTaskId(),
        request = request,
        onDurablyAccepted = onDurablyAccepted,
    )

    suspend fun acceptExport(
        taskId: UUID,
        request: AppExportRequest,
        onDurablyAccepted: () -> Unit = {},
    ): UUID = acceptPersistedTask(taskId, onDurablyAccepted = onDurablyAccepted) {
        store.insertExport(taskId, request, dependencies.nowMs())
    }

    suspend fun acceptShare(
        taskId: UUID,
        request: AppShareRequest,
        onDurablyAccepted: () -> Unit = {},
    ): UUID = acceptPersistedTask(taskId, onDurablyAccepted = onDurablyAccepted) {
        store.insertShare(taskId, request, dependencies.nowMs())
    }

    private suspend fun acceptPersistedTask(
        taskId: UUID,
        onDefiniteInsertFailure: () -> Unit = {},
        onDurablyAccepted: () -> Unit = {},
        insert: suspend () -> DataTaskState,
    ): UUID {
        val callerJob = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            val initialState = try {
                insert()
            } catch (failure: Throwable) {
                if (failure !is CancellationException) {
                    try {
                        onDefiniteInsertFailure()
                    } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) {
                            failure.addSuppressed(cleanupFailure)
                        }
                    }
                }
                throw failure
            }
            onDurablyAccepted()
            settleRejectedWake(taskId, initialState)
        }
        callerJob?.ensureActive()
        return taskId
    }

    private suspend fun settleRejectedWake(taskId: UUID, initialState: DataTaskState) {
        val result = wakeSignal.wake(taskId)
        if (result !is ServiceStartResult.Rejected) return
        val blockedState = when (result.reason) {
            ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED ->
                DataTaskState.START_BLOCKED_NOTIFICATION

            else -> DataTaskState.START_BLOCKED
        }
        store.compareAndSetStartBlocked(
            taskId = taskId,
            expectedState = initialState,
            blockedState = blockedState,
            nowMs = dependencies.nowMs(),
        )
    }
}
