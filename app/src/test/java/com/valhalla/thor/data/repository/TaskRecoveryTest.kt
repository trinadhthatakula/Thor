// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.job.*
import com.valhalla.thor.data.service.*
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.*
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import java.security.Provider
import java.security.Security
import java.security.spec.KeySpec
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactorySpi
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TaskRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var store: DataTaskStore
    private lateinit var sweepStore: RoomPrivilegeSweepStore
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "archive-key-worker") }
        .asCoroutineDispatcher()
    private val main = Executors.newSingleThreadExecutor { Thread(it, "archive-ui-main") }
        .asCoroutineDispatcher()
    private lateinit var keys: ArchiveKeyHolder
    private val sources = RestoreSourceGrantHolder()
    private val cipher = AppArchiveCipher()
    private val woken = mutableListOf<UUID>()
    private var wakeResult: ServiceStartResult = ServiceStartResult.Requested
    private val id = UUID.fromString("70000000-0000-0000-0000-000000000001")
    private val passphrase = "caller-owned".toCharArray()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = DataTaskStore(database.dataTaskDao())
        sweepStore = RoomPrivilegeSweepStore(database.privilegeSweepDao())
        keys = ArchiveKeyHolder(worker)
        derivationHook = {}
        Security.insertProviderAt(object :
            Provider("TaskRecoveryKdf", 1.0, "Records derivation context") {
            init {
                put("SecretKeyFactory.PBKDF2WithHmacSHA256", RecordingKeyFactory::class.java.name)
            }
        }, 1)
        setChannel(NotificationManager.IMPORTANCE_LOW)
    }

    @After
    fun tearDown() {
        keys.drop(id.toString())
        sources.dropTask(id)
        Security.removeProvider("TaskRecoveryKdf")
        derivationHook = {}
        worker.close()
        main.close()
        database.close()
    }

    @Test
    fun `confirmed cancelled destructive restore resumes the same UUID atomically`() = runTest {
        val snapshot = interruptedRestore()
        assertEquals(140L, snapshot.cancelRequestedAtEpochMs)
        val controller = controller()
        assertTrue(controller.perform(id, TaskAction.REVIEW_RESTORE) is TaskActionDispatch.Route)
        assertEquals(DataTaskState.INTERRUPTED_REVIEW, store.loadTask(id)?.state)

        assertEquals(TaskActionDispatch.Applied, controller.perform(id, TaskAction.RESUME))
        val resumed = requireNotNull(store.loadTask(id))
        assertEquals(id, resumed.taskId)
        assertEquals(DataTaskState.QUEUED, resumed.state)
        assertNull(resumed.cancelRequestedAtEpochMs)
        assertEquals(DataTaskItemState.PENDING, resumed.items.single().state)
        assertNull((resumed.detail as StoredDataTaskDetail.ArchiveRestore).mutationBreadcrumb)
        assertEquals(listOf(id), woken)
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.INVALID_STATE),
            controller.perform(id, TaskAction.RESUME)
        )
    }

    @Test
    fun `ordinary resume cannot consume destructive cancellation without confirmation`() = runTest {
        interruptedRestore()
        assertFalse(
            store.resumeFromUserAction(
                id, DataTaskState.INTERRUPTED_REVIEW,
                DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW, 160L
            )
        )
        assertEquals(140L, store.loadTask(id)?.cancelRequestedAtEpochMs)
    }

    @Test
    fun `review snapshot rejects intervening cancellation state or live ownership`() = runTest {
        val snapshot = interruptedRestore()
        val port = dataPort()
        val stale = object : DataTaskActionPort by port {
            override suspend fun load(taskId: UUID) = snapshot
        }
        val controller = controller(stale)
        val mutations = listOf(
            "cancel_requested_at_epoch_ms = 141",
            "state = 'CANCEL_REQUESTED'",
            "service_session_token = 'live'",
            "claim_token = 'live'",
            "claim_lease_expires_at_epoch_ms = 999",
            "kind = 'ARCHIVE_BACKUP'",
            "terminal_at_epoch_ms = 999",
        )
        for (mutation in mutations) {
            sql("UPDATE data_tasks SET $mutation WHERE task_id = '$id'")
            assertEquals(
                mutation,
                TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION),
                controller.perform(id, TaskAction.RESUME)
            )
            sql("UPDATE data_tasks SET cancel_requested_at_epoch_ms = 140, state = 'INTERRUPTED_REVIEW', kind = 'ARCHIVE_RESTORE', service_session_token = NULL, claim_token = NULL, claim_lease_expires_at_epoch_ms = NULL, terminal_at_epoch_ms = NULL WHERE task_id = '$id'")
        }
        assertTrue(woken.isEmpty())
    }

    @Test
    fun `failed destructive checkpoint reset rolls back cancellation consumption and item reset`() =
        runTest {
            interruptedRestore()
            sql("UPDATE archive_task_details SET destructive_started = 0 WHERE task_id = '$id'")
            val failure =
                runCatching { controller().perform(id, TaskAction.RESUME) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            val unchanged = requireNotNull(store.loadTask(id))
            assertEquals(DataTaskState.INTERRUPTED_REVIEW, unchanged.state)
            assertEquals(140L, unchanged.cancelRequestedAtEpochMs)
            assertEquals(DataTaskItemState.RUNNING, unchanged.items.single().state)
            assertTrue(woken.isEmpty())
        }

    @Test
    fun `backup launcher returns accepted UUID when blocked settlement fails after observer attaches`() =
        runTest {
            assertAcceptedAfterSettlementFailure(restore = false)
        }

    @Test
    fun `restore launcher returns accepted UUID and retains source when blocked settlement fails`() =
        runTest {
            assertAcceptedAfterSettlementFailure(restore = true)
        }

    @Test
    fun `archive launchers return null only before insertion and clean provisional ownership`() =
        runTest {
            for (restore in listOf(false, true)) {
                val acceptanceStore = object : DataTaskAcceptanceStore by store {
                    override suspend fun insertBackup(
                        taskId: UUID,
                        request: ArchiveBackupRequest,
                        nowMs: Long
                    ): DataTaskState = error("insert failed")

                    override suspend fun insertRestore(
                        taskId: UUID,
                        request: ArchiveRestoreRequest,
                        nowMs: Long
                    ): DataTaskState = error("insert failed")
                }
                assertNull(start(launcher(acceptanceStore), restore))
                assertNull(store.loadTask(id))
                assertNull(keys.currentToken(id.toString()))
                assertNull(sources.currentToken(id))
            }
            assertArrayEquals("caller-owned".toCharArray(), passphrase)
            assertTrue(woken.isEmpty())
        }

    @Test
    fun `data notification settings remain passive until explicit same UUID retry`() = runTest {
        notificationBlockedBackup()
        val retained = keys.put(id.toString(), SecretKeySpec(ByteArray(32), "AES"))
        val controller = controller()
        assertTrue(
            controller.perform(
                id,
                TaskAction.OPEN_NOTIFICATION_SETTINGS
            ) is TaskActionDispatch.Route
        )
        assertEquals(DataTaskState.START_BLOCKED_NOTIFICATION, store.loadTask(id)?.state)
        assertTrue(woken.isEmpty())
        assertEquals(TaskActionDispatch.Applied, controller.perform(id, TaskAction.RETRY))
        assertEquals(DataTaskState.QUEUED, store.loadTask(id)?.state)
        assertEquals(retained, keys.currentToken(id.toString()))
        assertEquals(listOf(id), woken)
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.INVALID_STATE),
            controller.perform(id, TaskAction.RETRY)
        )
    }

    @Test
    fun `disabled or missing data channel does not requeue or consume retained handoffs`() =
        runTest {
            notificationBlockedBackup()
            val token = keys.put(id.toString(), SecretKeySpec(ByteArray(32), "AES"))
            setChannel(NotificationManager.IMPORTANCE_NONE)
            assertEquals(
                TaskActionDispatch.Rejected(TaskActionRejection.START_REJECTED),
                controller().perform(id, TaskAction.RETRY)
            )
            context.getSystemService(NotificationManager::class.java)
                .deleteNotificationChannel(DATA_FOREGROUND_CHANNEL_ID)
            assertEquals(
                TaskActionDispatch.Rejected(TaskActionRejection.START_REJECTED),
                controller().perform(id, TaskAction.RETRY)
            )
            assertEquals(DataTaskState.START_BLOCKED_NOTIFICATION, store.loadTask(id)?.state)
            assertEquals(token, keys.currentToken(id.toString()))
            assertTrue(woken.isEmpty())
        }

    @Test
    fun `notification race after resume persists blocked state again without duplicating task`() =
        runTest {
            notificationBlockedBackup()
            wakeResult =
                ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED)
            assertEquals(
                TaskActionDispatch.Rejected(TaskActionRejection.START_REJECTED),
                controller().perform(id, TaskAction.RETRY)
            )
            assertEquals(DataTaskState.START_BLOCKED_NOTIFICATION, store.loadTask(id)?.state)
            assertEquals(listOf(id), woken)
        }

    @Test
    fun `backup reauthentication derives off caller main context and preserves passphrase`() =
        runTest {
            waitingForBackupAuthentication()
            var derivedOn: String? = null
            derivationHook = { derivedOn = Thread.currentThread().name }
            val result = withContext(main) { controller().submitArchivePassphrase(id, passphrase) }
            assertEquals(TaskActionDispatch.Applied, result)
            assertEquals("archive-key-worker", derivedOn)
            assertArrayEquals("caller-owned".toCharArray(), passphrase)
            assertNotNull(keys.currentToken(id.toString()))
            assertEquals(DataTaskState.QUEUED, store.loadTask(id)?.state)
        }

    @Test
    fun `cancellation during backup derivation leaves no provisional token or resume`() = runTest {
        waitingForBackupAuthentication()
        val action = async(main, start = CoroutineStart.LAZY) {
            controller().submitArchivePassphrase(
                id,
                passphrase
            )
        }
        derivationHook = { action.cancel() }
        action.start()
        try {
            action.await(); fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertNull(keys.currentToken(id.toString()))
        assertEquals(DataTaskState.WAITING_FOR_AUTH, store.loadTask(id)?.state)
        assertTrue(woken.isEmpty())
        assertArrayEquals("caller-owned".toCharArray(), passphrase)
    }

    @Test
    fun `sweep notification retry uses observed notification reason and the same UUID`() = runTest {
        notificationBlockedSweep()
        val controller = DefaultTaskActionController(dataPort(), sweepPort())
        assertTrue(
            controller.perform(
                id,
                TaskAction.OPEN_NOTIFICATION_SETTINGS
            ) is TaskActionDispatch.Route
        )
        assertEquals(PrivilegeSweepRequestState.BLOCKED, sweepStore.load(id)?.requestState)
        assertTrue(woken.isEmpty())
        assertEquals(TaskActionDispatch.Applied, controller.perform(id, TaskAction.RETRY))
        assertEquals(PrivilegeSweepRequestState.QUEUED, sweepStore.load(id)?.requestState)
        assertEquals(listOf(id), woken)
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.INVALID_STATE),
            controller.perform(id, TaskAction.RETRY)
        )
    }

    @Test
    fun `sweep retry keeps disabled invalid or unauthorized requests blocked without wake`() =
        runTest {
            notificationBlockedSweep()
            val available = sweepPort()
            for (state in listOf(
                ForegroundNotificationState.Blocked,
                ForegroundNotificationState.Invalid("missing")
            )) {
                val blocked = object : SweepTaskActionPort by available {
                    override fun notificationState() = state
                }
                assertEquals(
                    TaskActionDispatch.Rejected(TaskActionRejection.START_REJECTED),
                    DefaultTaskActionController(dataPort(), blocked).perform(id, TaskAction.RETRY)
                )
            }
            val denied = object : SweepTaskActionPort by available {
                override suspend fun refreshPrivilegeAndRead() = false
            }
            assertEquals(
                TaskActionDispatch.Rejected(TaskActionRejection.AUTHORIZATION_NOT_GRANTED),
                DefaultTaskActionController(dataPort(), denied).perform(id, TaskAction.RETRY)
            )
            assertEquals(
                PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION,
                sweepStore.load(id)?.blockReason
            )
            assertTrue(woken.isEmpty())
        }

    @Test
    fun `sweep notification preflight cannot override concurrent cancellation`() = runTest {
        notificationBlockedSweep()
        val port = sweepPort()
        val raced = object : SweepTaskActionPort by port {
            override suspend fun refreshPrivilegeAndRead(): Boolean {
                sweepStore.requestCancellation(id, 120L)
                return true
            }
        }
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION),
            DefaultTaskActionController(dataPort(), raced).perform(id, TaskAction.RETRY)
        )
        assertTrue(woken.isEmpty())
        assertNotEquals(PrivilegeSweepRequestState.QUEUED, sweepStore.load(id)?.requestState)
    }

    @Test
    fun `sweep notification start race settles the same UUID blocked again`() = runTest {
        notificationBlockedSweep()
        wakeResult = ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED)
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.START_REJECTED),
            DefaultTaskActionController(dataPort(), sweepPort()).perform(id, TaskAction.RETRY)
        )
        assertEquals(
            PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION,
            sweepStore.load(id)?.blockReason
        )
        assertEquals(listOf(id), woken)
    }

    @Test
    fun `notification retry preserves ordinary cancellation and all owner fences`() = runTest {
        notificationBlockedBackup()
        val snapshot = requireNotNull(store.loadTask(id))
        val port = dataPort()
        val controller = controller(object : DataTaskActionPort by port {
            override suspend fun load(taskId: UUID) = snapshot
        })
        for (mutation in listOf(
            "cancel_requested_at_epoch_ms = 123", "service_session_token = 'live'",
            "claim_token = 'live'", "claim_lease_expires_at_epoch_ms = 999", "state = 'RUNNING'"
        )) {
            sql("UPDATE data_tasks SET $mutation WHERE task_id = '$id'")
            assertEquals(
                mutation,
                TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION),
                controller.perform(id, TaskAction.RETRY)
            )
            sql("UPDATE data_tasks SET cancel_requested_at_epoch_ms = NULL, service_session_token = NULL, claim_token = NULL, claim_lease_expires_at_epoch_ms = NULL, state = 'START_BLOCKED_NOTIFICATION' WHERE task_id = '$id'")
        }
        assertTrue(woken.isEmpty())
    }

    @Test
    fun `notification retry does not consume restore handoffs or invent missing ones`() = runTest {
        store.insertRestore(id, restoreRequest(), 100L)
        store.compareAndSetStartBlocked(
            id,
            DataTaskState.STAGING_SOURCE,
            DataTaskState.START_BLOCKED_NOTIFICATION,
            110L
        )
        val key = keys.put(id.toString(), SecretKeySpec(ByteArray(32), "AES"))
        val source = sources.registerAuthorized(id, "content://test/archive")
        assertEquals(TaskActionDispatch.Applied, controller().perform(id, TaskAction.RETRY))
        assertEquals(key, keys.currentToken(id.toString()))
        assertEquals(source, sources.currentToken(id))
        store.compareAndSetStartBlocked(
            id,
            DataTaskState.QUEUED,
            DataTaskState.START_BLOCKED_NOTIFICATION,
            120L
        )
        keys.drop(id.toString())
        sources.dropTask(id)
        assertEquals(TaskActionDispatch.Applied, controller().perform(id, TaskAction.RETRY))
        assertNull(keys.currentToken(id.toString()))
        assertNull(sources.currentToken(id))
        assertEquals(
            StoredRestoreSource.AwaitingTransientGrant,
            (store.loadTask(id)?.detail as StoredDataTaskDetail.ArchiveRestore).source
        )
    }

    @Test
    fun `notification runtime permission denial still permits explicit retry`() = runTest {
        notificationBlockedBackup()
        val port = dataPort()
        val deniedPermission = object : DataTaskActionPort by port {
            override fun notificationState() = ForegroundNotificationState.PostPermissionDenied
        }
        assertEquals(
            TaskActionDispatch.Applied,
            controller(deniedPermission).perform(id, TaskAction.RETRY)
        )
        assertEquals(DataTaskState.QUEUED, store.loadTask(id)?.state)
    }

    @Test
    fun `backup resume failure drops its prepared token without clearing caller passphrase`() =
        runTest {
            waitingForBackupAuthentication()
            val port = dataPort()
            val failedResume = object : DataTaskActionPort by port {
                override suspend fun resume(
                    taskId: UUID, expectedState: DataTaskState,
                    expectedInterruption: DataTaskInterruption, sourceToken: UUID?,
                    confirmedDestructiveReview: Boolean, expectedCancellationAtMs: Long?
                ): Boolean = error("Room rejected transition")
            }
            assertTrue(runCatching {
                controller(failedResume).submitArchivePassphrase(
                    id,
                    passphrase
                )
            }.isFailure)
            assertNull(keys.currentToken(id.toString()))
            assertEquals(DataTaskState.WAITING_FOR_AUTH, store.loadTask(id)?.state)
            assertTrue(woken.isEmpty())
            assertArrayEquals("caller-owned".toCharArray(), passphrase)
        }

    @Test
    fun `expired backup key survives explicit retry only as authentication recovery not a recreated secret`() =
        runTest {
            notificationBlockedBackup()
            keys = ArchiveKeyHolder(StandardTestDispatcher(testScheduler))
            keys.put(id.toString(), SecretKeySpec(ByteArray(32), "AES"))
            runCurrent()
            advanceTimeBy(ArchiveKeyHolder.KEY_LIFETIME_MS + 1)
            runCurrent()
            assertNull(keys.currentToken(id.toString()))
            assertEquals(TaskActionDispatch.Applied, controller().perform(id, TaskAction.RETRY))
            assertNull(keys.currentToken(id.toString()))
            val claim = requireNotNull(
                database.dataTaskDao()
                    .claimOldestRunnableWork("session", "task", "item", 120L, 1_000L)
            )
            assertTrue(
                store.settleClaimedTask(
                    id, claim.task.claimToken, claim.item.ordinal,
                    claim.item.claimToken, archiveKeyUnavailableOutcome(), 130L
                )
            )
            assertEquals(DataTaskState.WAITING_FOR_AUTH, store.loadTask(id)?.state)
        }

    @Test
    fun `confirmed review without prior cancellation still requires explicit authorization`() =
        runTest {
            interruptedRestore()
            sql("UPDATE data_tasks SET cancel_requested_at_epoch_ms = NULL WHERE task_id = '$id'")
            assertFalse(
                store.resumeFromUserAction(
                    id, DataTaskState.INTERRUPTED_REVIEW,
                    DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW, 160L
                )
            )
            assertEquals(TaskActionDispatch.Applied, controller().perform(id, TaskAction.RESUME))
            assertEquals(DataTaskState.QUEUED, store.loadTask(id)?.state)
        }

    @Test
    fun `failed interrupted item reset rolls back task resume and cancellation clearing`() =
        runTest {
            interruptedRestore()
            sql("UPDATE data_task_items SET state = 'FAILED' WHERE task_id = '$id'")
            assertTrue(runCatching {
                controller().perform(
                    id,
                    TaskAction.RESUME
                )
            }.exceptionOrNull() is IllegalStateException)
            assertEquals(DataTaskState.INTERRUPTED_REVIEW, store.loadTask(id)?.state)
            assertEquals(140L, store.loadTask(id)?.cancelRequestedAtEpochMs)
            assertNotNull((store.loadTask(id)?.detail as StoredDataTaskDetail.ArchiveRestore).mutationBreadcrumb)
            assertTrue(woken.isEmpty())
        }

    @Test
    fun `archive launch cancellation after insertion propagates and retains accepted ownership`() =
        runTest {
            for (restore in listOf(false, true)) {
                var action: Deferred<UUID?>? = null
                val acceptanceStore = object : DataTaskAcceptanceStore by store {
                    override suspend fun insertBackup(
                        taskId: UUID,
                        request: ArchiveBackupRequest,
                        nowMs: Long
                    ): DataTaskState =
                        store.insertBackup(taskId, request, nowMs)
                            .also { requireNotNull(action).cancel() }

                    override suspend fun insertRestore(
                        taskId: UUID,
                        request: ArchiveRestoreRequest,
                        nowMs: Long
                    ): DataTaskState =
                        store.insertRestore(taskId, request, nowMs)
                            .also { requireNotNull(action).cancel() }
                }
                action =
                    async(start = CoroutineStart.LAZY) { start(launcher(acceptanceStore), restore) }
                action.start()
                try {
                    action.await(); fail("expected cancellation")
                } catch (_: CancellationException) {
                }
                assertNotNull(store.loadTask(id))
                assertNotNull(keys.currentToken(id.toString()))
                if (restore) assertNotNull(sources.currentToken(id))
                sql("DELETE FROM data_tasks WHERE task_id = '$id'")
                keys.drop(id.toString())
                sources.dropTask(id)
            }
            assertEquals(listOf(id, id), woken)
            assertArrayEquals("caller-owned".toCharArray(), passphrase)
        }

    @Test
    fun `invalid backup derivation leaves no key and keeps authentication available`() = runTest {
        waitingForBackupAuthentication()
        sql("UPDATE archive_task_details SET kdf_salt_base64 = 'invalid' WHERE task_id = '$id'")
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.AUTHORIZATION_NOT_GRANTED),
            controller().submitArchivePassphrase(id, passphrase)
        )
        assertNull(keys.currentToken(id.toString()))
        assertEquals(DataTaskState.WAITING_FOR_AUTH, store.loadTask(id)?.state)
        assertTrue(woken.isEmpty())
        assertArrayEquals("caller-owned".toCharArray(), passphrase)
    }

    private suspend fun notificationBlockedSweep() {
        sweepStore.createOrFindEquivalent(
            NewPrivilegeSweepSnapshot(
                requestId = id,
                workId = UUID.randomUUID(),
                operation = PrivilegeSweepOperation.CLEAR_CACHE,
                freezerMode = null,
                userId = 0,
                source = PrivilegeSweepSource.APP_LIST,
                createdAtEpochMs = 100L,
                targets = listOf("app.sweep"),
            )
        )
        assertTrue(
            sweepStore.markUnclaimedStartBlocked(
                id,
                PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION,
                110L
            )
        )
    }

    private fun sweepPort() = object : SweepTaskActionPort {
        override suspend fun load(taskId: UUID) = sweepStore.load(taskId)
        override suspend fun cancel(taskId: UUID) {
            sweepStore.requestCancellation(taskId, 120L)
        }

        override suspend fun acknowledge(taskId: UUID) =
            sweepStore.acknowledgeTerminalRequest(taskId, 120L) != null

        override suspend fun refreshPrivilegeAndRead() = true
        override suspend fun resumeBlocked(
            taskId: UUID,
            expectedReason: PrivilegeSweepBlockReason
        ) =
            sweepStore.resumeBlockedRequest(taskId, expectedReason, 120L)

        override suspend fun authorizeTargetRetry(taskId: UUID, ordinal: Int) = false
        override fun notificationState() = ForegroundNotificationState.Available
        override fun wake(taskId: UUID): ServiceStartResult {
            woken += taskId; return wakeResult
        }

        override suspend fun markStartBlocked(taskId: UUID, notificationBlocked: Boolean) =
            sweepStore.markUnclaimedStartBlocked(
                taskId,
                if (notificationBlocked) PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION else PrivilegeSweepBlockReason.START_BLOCKED,
                130L
            )
    }

    private suspend fun TestScope.assertAcceptedAfterSettlementFailure(restore: Boolean) {
        val attached = CompletableDeferred<Unit>()
        val observed =
            async { store.observeTask(id).filterNotNull().first().also { attached.complete(Unit) } }
        var inserts = 0
        val acceptanceStore = object : DataTaskAcceptanceStore by store {
            override suspend fun insertBackup(
                taskId: UUID,
                request: ArchiveBackupRequest,
                nowMs: Long
            ): DataTaskState {
                inserts++
                return store.insertBackup(taskId, request, nowMs).also { attached.await() }
            }

            override suspend fun insertRestore(
                taskId: UUID,
                request: ArchiveRestoreRequest,
                nowMs: Long
            ): DataTaskState {
                inserts++
                return store.insertRestore(taskId, request, nowMs).also { attached.await() }
            }

            override suspend fun compareAndSetStartBlocked(
                taskId: UUID,
                expectedState: DataTaskState,
                blockedState: DataTaskState,
                nowMs: Long
            ): Boolean = error("blocked settlement failed")
        }
        wakeResult = ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED)
        val result = start(launcher(acceptanceStore), restore)
        assertEquals(id, observed.await().taskId)
        assertEquals(id, result)
        assertEquals(1, inserts)
        assertNotNull(keys.currentToken(id.toString()))
        if (restore) assertNotNull(sources.currentToken(id))
        assertArrayEquals("caller-owned".toCharArray(), passphrase)
    }

    private fun launcher(acceptanceStore: DataTaskAcceptanceStore): ThorJobLauncher {
        val acceptance = DataTaskAcceptance(
            store = acceptanceStore,
            deriveKey = { _, _, _ -> SecretKeySpec(ByteArray(32), "AES") },
            keyVault = object : DataTaskKeyVault {
                override fun put(taskId: UUID, key: SecretKey) {
                    keys.put(taskId.toString(), key)
                }

                override fun drop(taskId: UUID) {
                    keys.drop(taskId.toString())
                }
            },
            restoreSourceVault = object : DataTaskRestoreSourceVault {
                override fun put(taskId: UUID, uriString: String) {
                    sources.registerAuthorized(taskId, uriString)
                }

                override fun drop(taskId: UUID) {
                    sources.dropTask(taskId)
                }
            },
            wakeSignal = wakeSignal(), taskIdFactory = { id }, clock = { 100L },
        )
        return ThorJobLauncher(
            context,
            keys,
            acceptance,
            cancellation(),
            database.dataTaskDao(),
            worker,
            worker
        )
    }

    private suspend fun start(launcher: ThorJobLauncher, restore: Boolean): UUID? =
        if (restore) launcher.startRestore(restoreRequest(), passphrase, ByteArray(16), 100)
        else launcher.startBackup(backupRequest(), passphrase)

    private fun controller(port: DataTaskActionPort = dataPort()) =
        DefaultTaskActionController(port, emptySweeps())

    private fun dataPort() = RoomDataTaskActionPort(
        dao = database.dataTaskDao(), cancellation = cancellation(), cipher = cipher, keys = keys,
        restoreSources = sources,
        restoreSourceStager = RestoreSourceStager(
            takeSource = { _, _ -> error("unexpected source consumption") },
            copyToPrivate = { _, _, _ -> error("unexpected copy") },
            commitPrivateSource = { _, _, _ -> error("unexpected source commit") },
            privateSourceUri = { _, _ -> null }, discardPrivateSource = { _, _ -> },
            discardUncommittedTaskSources = {}, nowMs = { 100L },
        ),
        archiveSources = object : ArchiveSourceFactory {
            override suspend fun open(uriString: String): ArchiveOpenOutcome =
                error("unexpected source open")
        },
        openArchive = OpenArchiveUseCase(cipher, worker), wakeSignal = wakeSignal(),
        context = context, defaultDispatcher = worker,
    )

    private fun cancellation() = DataTaskCancellationCoordinator(
        requestCancellation = { store.requestCancellation(it, 140L) },
        dropArchiveKey = { keys.drop(it.toString()) },
        dropRestoreSource = sources::dropTask,
        cancelActive = { false },
        wakeQueue = { ServiceStartResult.Requested },
        settleStaleClaim = { false },
    )

    private fun emptySweeps() = object : SweepTaskActionPort {
        override suspend fun load(taskId: UUID): StoredPrivilegeSweep? = null
        override suspend fun cancel(taskId: UUID) = error("unexpected sweep")
        override suspend fun acknowledge(taskId: UUID): Boolean = error("unexpected sweep")
        override suspend fun refreshPrivilegeAndRead(): Boolean = error("unexpected sweep")
        override suspend fun resumeBlocked(
            taskId: UUID,
            expectedReason: PrivilegeSweepBlockReason
        ): Boolean = error("unexpected sweep")

        override suspend fun authorizeTargetRetry(taskId: UUID, ordinal: Int): Boolean =
            error("unexpected sweep")

        override fun notificationState() = ForegroundNotificationState.Available
        override fun wake(taskId: UUID): ServiceStartResult = error("unexpected sweep")
        override suspend fun markStartBlocked(taskId: UUID, notificationBlocked: Boolean): Boolean =
            error("unexpected sweep")
    }

    private fun wakeSignal() = DataQueueWakeSignal { woken += it; wakeResult }
    private fun backupRequest() =
        ArchiveBackupRequest("app.backup", setOf(DataClass.CE), true, ByteArray(16))

    private fun restoreRequest() =
        ArchiveRestoreRequest("content://test/archive", "app.restore", setOf(DataClass.CE), false)

    private suspend fun interruptedRestore(): DataTaskSnapshot {
        store.insertRestore(id, restoreRequest(), 100L)
        val dao = database.dataTaskDao()
        requireNotNull(dao.claimOldestRunnableWork("session", "task", "item", 110L, 1_000L))
        assertTrue(
            dao.checkpointClaimedTask(
                id.toString(), "task", 0, "item",
                DataTaskCheckpoint(
                    DataTaskStage.RESTORING, 0L, 1L, 0, "Restore", true,
                    RestoreMutationBreadcrumb("app.restore", "Restore", 120L), 120L
                ), 1_000L,
                transactionNowMs = { 120L })
        )
        store.requestCancellation(id, 140L)
        assertTrue(
            store.settleClaimedTask(
                id,
                "task",
                0,
                "item",
                DataTaskRunOutcome.Cancelled,
                150L
            )
        )
        return requireNotNull(store.loadTask(id))
    }

    private suspend fun notificationBlockedBackup() {
        store.insertBackup(id, backupRequest(), 100L)
        assertTrue(
            store.compareAndSetStartBlocked(
                id,
                DataTaskState.QUEUED,
                DataTaskState.START_BLOCKED_NOTIFICATION,
                110L
            )
        )
    }

    private suspend fun waitingForBackupAuthentication() {
        store.insertBackup(id, backupRequest(), 100L)
        requireNotNull(
            database.dataTaskDao().claimOldestRunnableWork("session", "task", "item", 110L, 1_000L)
        )
        assertTrue(
            store.settleClaimedTask(
                id,
                "task",
                0,
                "item",
                DataTaskRunOutcome.WaitingForAuthentication(DataTaskResultCode("AUTHENTICATION_REQUIRED")),
                120L
            )
        )
    }

    private fun setChannel(importance: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DATA_FOREGROUND_CHANNEL_ID,
                "Data",
                importance
            )
        )
    }

    private fun sql(statement: String) {
        database.openHelper.writableDatabase.execSQL(statement)
    }

    class RecordingKeyFactory : SecretKeyFactorySpi() {
        override fun engineGenerateSecret(keySpec: KeySpec): SecretKey {
            derivationHook()
            return SecretKeySpec(ByteArray(32), "AES")
        }

        override fun engineGetKeySpec(key: SecretKey, keySpec: Class<*>): KeySpec = error("unused")
        override fun engineTranslateKey(key: SecretKey): SecretKey = error("unused")
    }

    companion object {
        @Volatile
        private var derivationHook: () -> Unit = {}
    }
}
