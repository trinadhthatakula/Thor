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
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.repository.ThorJobWatcher
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    fun sharePersistsExactSelectionBeforeAcceptanceCallbackAndWakeWithoutSecrets() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingAcceptanceStore(events)
        val keyVault = RecordingKeyVault(events)
        val acceptance = acceptance(store, keyVault, events)

        val accepted = acceptance.acceptShare(TASK_ID, shareRequest()) { events += "accepted" }

        assertEquals(TASK_ID, accepted)
        assertEquals(listOf("insert", "accepted", "wake"), events)
        assertEquals(DataTaskState.QUEUED, store.states[TASK_ID])
        assertTrue(keyVault.ids().isEmpty())
    }

    @Test
    fun shareWakeRejectionRetainsDurableIdentityAndBlockedState() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingAcceptanceStore(events)
        val acceptance = acceptance(
            store, RecordingKeyVault(events), events,
            wakeResult = ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED),
        )

        assertEquals(TASK_ID, acceptance.acceptShare(TASK_ID, shareRequest()))
        assertEquals(DataTaskState.START_BLOCKED_NOTIFICATION, store.states[TASK_ID])
        assertEquals(listOf("insert", "wake", "block:QUEUED:START_BLOCKED_NOTIFICATION"), events)
    }

    @Test
    fun shareLauncherDoesNotRejectPersistedTaskWhenWakeThrows() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingAcceptanceStore(events)
        val acceptance = acceptance(store, RecordingKeyVault(events), events, wake = {
            error("Android wake failure")
        })

        assertEquals(TASK_ID, ShareTaskLauncherImpl(acceptance).startShare(TASK_ID, shareRequest()))
        assertEquals(DataTaskState.QUEUED, store.states[TASK_ID])
    }

    @Test
    fun shareLauncherRejectsOnlyDefiniteInsertionFailure() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingAcceptanceStore(events).apply { insertFailure = IllegalStateException("disk") }
        val acceptance = acceptance(store, RecordingKeyVault(events), events)

        assertEquals(null, ShareTaskLauncherImpl(acceptance).startShare(TASK_ID, shareRequest()))
        assertEquals(listOf("insert"), events)
        assertTrue(store.states.isEmpty())
    }

    private fun shareRequest() = com.valhalla.thor.domain.model.AppShareRequest(
        listOf(com.valhalla.thor.domain.model.AppShareTarget("com.example.share", "Share")),
    )

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
        val sourceVault = RecordingRestoreSourceVault(events)
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
            restoreSourceVault = sourceVault,
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
        assertEquals(listOf("derive", "put-key", "put-source", "insert", "wake"), events)
        assertTrue(sourceVault.contains(TASK_ID))
        assertEquals(DataTaskState.STAGING_SOURCE, store.states.getValue(TASK_ID))
    }

    @Test
    fun restoreInsertFailureDropsTheTransientSourceAndKey() = runTest {
        val events = mutableListOf<String>()
        val keyVault = RecordingKeyVault(events)
        val sourceVault = RecordingRestoreSourceVault(events)
        val store = RecordingAcceptanceStore(events).apply {
            insertFailure = IllegalStateException("insert failed")
        }
        val acceptance = DataTaskAcceptance(
            store = store,
            deriveKey = { _, _, _ ->
                events += "derive"
                KEY
            },
            keyVault = keyVault,
            restoreSourceVault = sourceVault,
            wakeSignal = DataQueueWakeSignal { error("must not wake") },
            taskIdFactory = { TASK_ID },
            clock = { NOW_MS },
        )

        expectFailure<IllegalStateException> {
            acceptance.acceptRestore(
                request = restoreRequest(),
                passphrase = PASSPHRASE,
                salt = ByteArray(16) { 7 },
                iterations = 123_456,
            )
        }

        assertEquals(
            listOf("derive", "put-key", "put-source", "insert", "drop-key", "drop-source"),
            events,
        )
        assertFalse(keyVault.contains(TASK_ID))
        assertFalse(sourceVault.contains(TASK_ID))
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
    fun callerOwnedExportIdIsInsertedAndReturned() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingAcceptanceStore(events)
        val acceptance = DataTaskAcceptance(
            store = store,
            deriveKey = { _, _, _ -> KEY },
            keyVault = RecordingKeyVault(events),
            wakeSignal = DataQueueWakeSignal { taskId ->
                assertEquals(CALLER_TASK_ID, taskId)
                events += "wake"
                ServiceStartResult.Requested
            },
            taskIdFactory = { error("caller-owned export must not allocate another ID") },
            clock = { NOW_MS },
        )

        val accepted = acceptance.acceptExport(CALLER_TASK_ID, exportRequest())

        assertEquals(CALLER_TASK_ID, accepted)
        assertEquals(DataTaskState.QUEUED, store.states.getValue(CALLER_TASK_ID))
        assertEquals(listOf("insert", "wake"), events)
    }

    @Test
    fun exportLauncherReturnsTheDurableIdWhenWakeSettlementFailsAfterInsertion() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingAcceptanceStore(events).apply {
            blockFailure = IllegalStateException("start-blocked settlement failed")
        }
        val acceptance = acceptance(
            store = store,
            keyVault = RecordingKeyVault(events),
            events = events,
            wakeResult = ServiceStartResult.Rejected(ServiceStartFailure.SECURITY_EXCEPTION),
        )
        val launcher = ExportJobLauncherImpl(
            acceptance = acceptance,
            watcher = object : ThorJobWatcher {
                override fun status(jobId: UUID): Flow<ThorJobStatus> = emptyFlow()
                override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> =
                    emptyFlow()
            },
        )

        val accepted = launcher.startExport(TASK_ID, exportRequest())

        assertEquals(TASK_ID, accepted)
        assertEquals(DataTaskState.QUEUED, store.states.getValue(TASK_ID))
        assertEquals(listOf("insert", "wake", "block:QUEUED:START_BLOCKED"), events)
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

    @Test
    fun `public archive and single export launchers no longer construct WorkRequests`() {
        listOf("ThorJobLauncher.kt", "ExportJobLauncherImpl.kt").forEach { fileName ->
            val source = productionSource(fileName)
            assertTrue(
                "$fileName must use durable acceptance",
                source.contains("DataTaskAcceptance")
            )
            assertFalse(
                "$fileName must not create new data WorkRequests",
                source.contains("OneTimeWorkRequestBuilder<ArchiveBackupWorker>") ||
                        source.contains("OneTimeWorkRequestBuilder<ArchiveRestoreWorker>") ||
                        source.contains("OneTimeWorkRequestBuilder<AppExportWorker>"),
            )
        }
    }

    private fun productionSource(fileName: String): String {
        var directory = java.io.File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val source = java.io.File(
                directory,
                "app/src/main/java/com/valhalla/thor/data/backup/job/$fileName",
            )
            if (source.isFile) return source.readText()
            directory = directory.parentFile ?: error("Could not find project root")
        }
        error("Could not find production source $fileName")
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
        var blockFailure: Throwable? = null
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

        override suspend fun insertShare(
            taskId: UUID,
            request: com.valhalla.thor.domain.model.AppShareRequest,
            nowMs: Long,
        ): DataTaskState = insert(taskId, DataTaskState.QUEUED)

        override suspend fun compareAndSetStartBlocked(
            taskId: UUID,
            expectedState: DataTaskState,
            blockedState: DataTaskState,
            nowMs: Long,
        ): Boolean {
            events += "block:$expectedState:$blockedState"
            blockFailure?.let { throw it }
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

    private class RecordingRestoreSourceVault(
        private val events: MutableList<String>,
    ) : DataTaskRestoreSourceVault {
        private val sources = mutableMapOf<UUID, String>()

        override fun put(taskId: UUID, uriString: String) {
            events += "put-source"
            sources[taskId] = uriString
        }

        override fun drop(taskId: UUID) {
            events += "drop-source"
            sources.remove(taskId)
        }

        fun contains(taskId: UUID): Boolean = taskId in sources
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val CALLER_TASK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000006")
        val PASSPHRASE = "correct horse battery staple".toCharArray()
        val KEY: SecretKey = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        const val NOW_MS = 5_000L
    }
}
