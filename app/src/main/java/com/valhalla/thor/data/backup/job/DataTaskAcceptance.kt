// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.model.AppExportRequest
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

/**
 * Accepts data work by durably inserting it before asking Android to wake the executor.
 *
 * This class is deliberately unannotated. Task 9 binds it only when the service, manifest component,
 * and concrete wake signal are activated atomically.
 */
internal class DataTaskAcceptance internal constructor(
    private val store: DataTaskAcceptanceStore,
    private val deriveKey: (passphrase: CharArray, salt: ByteArray, iterations: Int) -> SecretKey,
    private val keyVault: DataTaskKeyVault,
    private val wakeSignal: DataQueueWakeSignal,
    private val taskIdFactory: () -> UUID = UUID::randomUUID,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(
        store: DataTaskStore,
        archiveCipher: AppArchiveCipher,
        archiveKeyHolder: ArchiveKeyHolder,
        wakeSignal: DataQueueWakeSignal,
    ) : this(
        store = store,
        deriveKey = archiveCipher::deriveKey,
        keyVault = ArchiveKeyHolderVault(archiveKeyHolder),
        wakeSignal = wakeSignal,
    )

    suspend fun acceptBackup(
        request: ArchiveBackupRequest,
        passphrase: CharArray,
    ): UUID {
        val taskId = taskIdFactory()
        val key = deriveKey(passphrase, request.salt, KDF_ITERATIONS)
        keyVault.put(taskId, key)
        return acceptPersistedTask(
            taskId = taskId,
            onDefiniteInsertFailure = { keyVault.drop(taskId) },
        ) {
            store.insertBackup(taskId, request, clock())
        }
    }

    suspend fun acceptRestore(
        request: ArchiveRestoreRequest,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): UUID {
        val taskId = taskIdFactory()
        val key = deriveKey(passphrase, salt, iterations)
        keyVault.put(taskId, key)
        return acceptPersistedTask(
            taskId = taskId,
            onDefiniteInsertFailure = { keyVault.drop(taskId) },
        ) {
            store.insertRestore(taskId, request, clock())
        }
    }

    suspend fun acceptExport(request: AppExportRequest): UUID {
        val taskId = taskIdFactory()
        return acceptPersistedTask(taskId) {
            store.insertExport(taskId, request, clock())
        }
    }

    private suspend fun acceptPersistedTask(
        taskId: UUID,
        onDefiniteInsertFailure: () -> Unit = {},
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
            nowMs = clock(),
        )
    }

    private class ArchiveKeyHolderVault(
        private val holder: ArchiveKeyHolder,
    ) : DataTaskKeyVault {
        override fun put(taskId: UUID, key: SecretKey) {
            holder.put(taskId.toString(), key)
        }

        override fun drop(taskId: UUID) {
            holder.drop(taskId.toString())
        }
    }
}
