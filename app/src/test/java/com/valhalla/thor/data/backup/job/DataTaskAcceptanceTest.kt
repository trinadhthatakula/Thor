// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskState
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DataTaskAcceptanceTest {

    @Test
    fun keyIsAvailableBeforeBackupBecomesRunnable() = runTest {
        val events = mutableListOf<String>()
        val keyVault = RecordingKeyVault(events)
        val store = RecordingAcceptanceStore(events).apply {
            beforeInsert = { assertTrue(keyVault.contains(TASK_ID)) }
        }
        val acceptance = acceptance(
            store = store,
            keyVault = keyVault,
            events = events,
        )

        val accepted = acceptance.acceptBackup(backupRequest(), PASSPHRASE)

        assertEquals(TASK_ID, accepted)
        assertEquals(listOf("derive", "put-key", "insert", "wake"), events)
        assertEquals(DataTaskState.QUEUED, store.states.getValue(TASK_ID))
    }

    @Test
    fun restoreUsesTheArchiveKdfInputsAndKeepsTheCallerPassphraseOwnedByCaller() = runTest {
        val events = mutableListOf<String>()
        val keyVault = RecordingKeyVault(events)
        val store = RecordingAcceptanceStore(events)
        var capturedPassphrase: CharArray? = null
        var capturedSalt: ByteArray? = null
        var capturedIterations: Int? = null
        val acceptance = DataTaskAcceptance(
            store = store,
            deriveKey = { passphrase, salt, iterations ->
                events += "derive"
                capturedPassphrase = passphrase
                capturedSalt = salt
                capturedIterations = iterations
                KEY
            },
            keyVault = keyVault,
            wakeSignal = DataQueueWakeSignal {
                events += "wake"
                ServiceStartResult.Requested
            },
            taskIdFactory = { TASK_ID },
            clock = { NOW_MS },
        )
        val salt = ByteArray(16) { 7 }

        acceptance.acceptRestore(restoreRequest(), PASSPHRASE, salt, 123_456)

        assertSame(PASSPHRASE, capturedPassphrase)
        assertArrayEquals(salt, capturedSalt)
        assertEquals(123_456, capturedIterations)
        assertEquals(listOf("derive", "put-key", "insert", "wake"), events)
        assertEquals(DataTaskState.STAGING_SOURCE, store.states.getValue(TASK_ID))
    }

    @Test
    fun insertFailureDropsTheKeyAndNeverWakes() = runTest {
        val events = mutableListOf<String>()
        val keyVault = RecordingKeyVault(events)
        val store = RecordingAcceptanceStore(events).apply {
            insertFailure = IllegalStateException("insert failed")
        }
        val acceptance = acceptance(store, keyVault, events)

        val failure = expectFailure<IllegalStateException> {
            acceptance.acceptBackup(backupRequest(), PASSPHRASE)
        }

        assertEquals("insert failed", failure.message)
        assertEquals(listOf("derive", "put-key", "insert", "drop-key"), events)
        assertFalse(keyVault.contains(TASK_ID))
        assertTrue(store.states.isEmpty())
    }

    @Test
    fun insertFailureRemainsPrimaryWhenKeyCleanupAlsoFails() = runTest {
        val events = mutableListOf<String>()
        val insertFailure = IllegalStateException("insert failed")
        val cleanupFailure = IllegalArgumentException("cleanup failed")
        val keyVault = RecordingKeyVault(events).apply {
            dropFailure = cleanupFailure
        }
        val store = RecordingAcceptanceStore(events).apply {
            this.insertFailure = insertFailure
        }
        val acceptance = acceptance(store, keyVault, events)

        val failure = expectFailure<IllegalStateException> {
            acceptance.acceptBackup(backupRequest(), PASSPHRASE)
        }

        assertSame(insertFailure, failure.cause ?: failure)
        assertEquals(listOf(cleanupFailure), insertFailure.suppressed.toList())
        assertEquals(listOf("derive", "put-key", "insert", "drop-key"), events)
    }

    @Test
    fun backupCancellationAtCommittedInsertBoundaryKeepsAcceptedTaskAndKey() = runTest {
        assertCancellationAtCommittedInsertBoundary(
            expectedState = DataTaskState.QUEUED,
            accept = { it.acceptBackup(backupRequest(), PASSPHRASE) },
        )
    }

    @Test
    fun restoreCancellationAtCommittedInsertBoundaryKeepsAcceptedTaskAndKey() = runTest {
        assertCancellationAtCommittedInsertBoundary(
            expectedState = DataTaskState.STAGING_SOURCE,
            accept = {
                it.acceptRestore(
                    request = restoreRequest(),
                    passphrase = PASSPHRASE,
                    salt = ByteArray(16) { 7 },
                    iterations = 123_456,
                )
            },
        )
    }

    @Test
    fun notificationWakeRejectionRetainsTheAcceptedTaskAsNotificationBlocked() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingAcceptanceStore(events)
        val acceptance = acceptance(
            store = store,
            keyVault = RecordingKeyVault(events),
            events = events,
            wakeResult = ServiceStartResult.Rejected(
                ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED,
            ),
        )

        val accepted = acceptance.acceptExport(exportRequest())

        assertEquals(TASK_ID, accepted)
        assertEquals(DataTaskState.START_BLOCKED_NOTIFICATION, store.states.getValue(TASK_ID))
        assertEquals(
            listOf("insert", "wake", "block:QUEUED:START_BLOCKED_NOTIFICATION"),
            events,
        )
    }

    @Test
    fun everyOtherWakeRejectionRetainsTheAcceptedTaskAsStartBlocked() = runTest {
        ServiceStartFailure.entries
            .filterNot { it == ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED }
            .forEach { failure ->
                val events = mutableListOf<String>()
                val store = RecordingAcceptanceStore(events)
                val acceptance = acceptance(
                    store = store,
                    keyVault = RecordingKeyVault(events),
                    events = events,
                    wakeResult = ServiceStartResult.Rejected(failure),
                )

                val accepted = acceptance.acceptExport(exportRequest())

                assertEquals(TASK_ID, accepted)
                assertEquals(DataTaskState.START_BLOCKED, store.states.getValue(TASK_ID))
                assertEquals(listOf("insert", "wake", "block:QUEUED:START_BLOCKED"), events)
            }
    }

    @Test
    fun acceptedWakeResultsLeaveTheTaskRunnable() = runTest {
        listOf(ServiceStartResult.Requested, ServiceStartResult.AlreadyRunning).forEach { result ->
            val events = mutableListOf<String>()
            val store = RecordingAcceptanceStore(events)
            val acceptance = acceptance(
                store = store,
                keyVault = RecordingKeyVault(events),
                events = events,
                wakeResult = result,
            )

            assertEquals(TASK_ID, acceptance.acceptExport(exportRequest()))
            assertEquals(DataTaskState.QUEUED, store.states.getValue(TASK_ID))
            assertEquals(listOf("insert", "wake"), events)
        }
    }

    @Test
    fun callerCancellationAfterInsertionDoesNotDeleteTheAcceptedTaskOrKey() = runTest {
        val events = mutableListOf<String>()
        val keyVault = RecordingKeyVault(events)
        val store = RecordingAcceptanceStore(events)
        val acceptance = acceptance(
            store = store,
            keyVault = keyVault,
            events = events,
            wake = {
                events += "wake"
                throw CancellationException("caller cancelled")
            },
        )

        expectFailure<CancellationException> {
            acceptance.acceptBackup(backupRequest(), PASSPHRASE)
        }

        assertEquals(DataTaskState.QUEUED, store.states.getValue(TASK_ID))
        assertTrue(keyVault.contains(TASK_ID))
        assertEquals(listOf("derive", "put-key", "insert", "wake"), events)
    }

    @Test
    fun exportAcceptanceDoesNotCreateOrStoreAnArchiveKey() = runTest {
        val events = mutableListOf<String>()
        val keyVault = RecordingKeyVault(events)
        val acceptance = acceptance(
            store = RecordingAcceptanceStore(events),
            keyVault = keyVault,
            events = events,
        )

        acceptance.acceptExport(exportRequest())

        assertTrue(keyVault.ids().isEmpty())
        assertEquals(listOf("insert", "wake"), events)
    }

    private suspend fun TestScope.assertCancellationAtCommittedInsertBoundary(
        expectedState: DataTaskState,
        accept: suspend (DataTaskAcceptance) -> UUID,
    ) {
        val events = mutableListOf<String>()
        val keyVault = RecordingKeyVault(events)
        lateinit var caller: Deferred<UUID>
        val store = RecordingAcceptanceStore(events).apply {
            afterInsertCommit = {
                assertEquals(expectedState, states.getValue(TASK_ID))
                caller.cancel(CancellationException("caller cancelled after commit"))
                yield()
            }
        }
        val acceptance = acceptance(store, keyVault, events)
        caller = async(start = CoroutineStart.LAZY) {
            accept(acceptance)
        }

        caller.start()
        val failure = expectFailure<CancellationException> {
            caller.await()
        }

        assertEquals("caller cancelled after commit", failure.message)
        assertEquals(expectedState, store.states.getValue(TASK_ID))
        assertTrue(keyVault.contains(TASK_ID))
        assertEquals(listOf("derive", "put-key", "insert", "wake"), events)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError(
                "Expected ${T::class.java.name}, got ${throwable::class.java.name}",
                throwable
            )
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }

    private fun acceptance(
        store: RecordingAcceptanceStore,
        keyVault: RecordingKeyVault,
        events: MutableList<String>,
        wakeResult: ServiceStartResult = ServiceStartResult.Requested,
        wake: ((UUID) -> ServiceStartResult)? = null,
    ) = DataTaskAcceptance(
        store = store,
        deriveKey = { _, _, _ ->
            events += "derive"
            KEY
        },
        keyVault = keyVault,
        wakeSignal = DataQueueWakeSignal { taskId ->
            check(taskId == TASK_ID)
            wake?.invoke(taskId) ?: run {
                events += "wake"
                wakeResult
            }
        },
        taskIdFactory = { TASK_ID },
        clock = { NOW_MS },
    )

    private fun backupRequest() = ArchiveBackupRequest(
        packageName = "com.example.backup",
        classes = setOf(DataClass.EXTERNAL_MEDIA, DataClass.CE),
        includeBundle = true,
        salt = ByteArray(16) { 3 },
    )

    private fun restoreRequest() = ArchiveRestoreRequest(
        uriString = "content://documents/raw-restore-uri-must-not-be-persisted",
        packageName = "com.example.restore",
        classes = setOf(DataClass.DE, DataClass.CE),
        restoreObb = true,
    )

    private fun exportRequest() = AppExportRequest(
        packageName = "com.example.export",
        format = BundleFormat.APKS,
        label = "Example Export",
    )

    private class RecordingAcceptanceStore(
        private val events: MutableList<String>,
    ) : DataTaskAcceptanceStore {
        val states = mutableMapOf<UUID, DataTaskState>()
        var insertFailure: Throwable? = null
        var beforeInsert: (() -> Unit)? = null
        var afterInsertCommit: (suspend () -> Unit)? = null

        override suspend fun insertBackup(
            taskId: UUID,
            request: ArchiveBackupRequest,
            nowMs: Long,
        ): DataTaskState = insert(taskId, DataTaskState.QUEUED)

        override suspend fun insertRestore(
            taskId: UUID,
            request: ArchiveRestoreRequest,
            nowMs: Long,
        ): DataTaskState = insert(taskId, DataTaskState.STAGING_SOURCE)

        override suspend fun insertExport(
            taskId: UUID,
            request: AppExportRequest,
            nowMs: Long,
        ): DataTaskState = insert(taskId, DataTaskState.QUEUED)

        override suspend fun compareAndSetStartBlocked(
            taskId: UUID,
            expectedState: DataTaskState,
            blockedState: DataTaskState,
            nowMs: Long,
        ): Boolean {
            events += "block:$expectedState:$blockedState"
            if (states[taskId] != expectedState) return false
            states[taskId] = blockedState
            return true
        }

        private suspend fun insert(taskId: UUID, state: DataTaskState): DataTaskState {
            events += "insert"
            beforeInsert?.invoke()
            insertFailure?.let { throw it }
            states[taskId] = state
            afterInsertCommit?.invoke()
            return state
        }
    }

    private class RecordingKeyVault(
        private val events: MutableList<String>,
    ) : DataTaskKeyVault {
        private val keys = mutableMapOf<UUID, SecretKey>()
        var dropFailure: Throwable? = null

        override fun put(taskId: UUID, key: SecretKey) {
            events += "put-key"
            keys[taskId] = key
        }

        override fun drop(taskId: UUID) {
            events += "drop-key"
            dropFailure?.let { throw it }
            keys.remove(taskId)
        }

        fun contains(taskId: UUID): Boolean = taskId in keys

        fun ids(): Set<UUID> = keys.keys
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val PASSPHRASE = "correct horse battery staple".toCharArray()
        val KEY: SecretKey = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        const val NOW_MS = 5_000L
    }
}
