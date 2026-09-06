// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.valhalla.thor.data.backup.job.AppExportWorker
import com.valhalla.thor.data.backup.job.ArchiveBackupWorker
import com.valhalla.thor.data.backup.job.ArchiveRestoreWorker
import com.valhalla.thor.data.backup.job.DataTaskCheckpointSink
import com.valhalla.thor.data.backup.job.DataTaskExecutionRequest
import com.valhalla.thor.data.backup.job.DataTaskRunner
import com.valhalla.thor.data.backup.job.DataTaskSinkWrite
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.data.backup.job.JobSheetTargets
import com.valhalla.thor.data.backup.job.LegacyWorkerResultSink
import com.valhalla.thor.data.backup.job.ThorJobNotifications
import com.valhalla.thor.data.backup.job.decodeLegacyAppExportRequest
import com.valhalla.thor.data.backup.job.decodeLegacyArchiveBackupRequest
import com.valhalla.thor.data.backup.job.decodeLegacyArchiveRestoreRequest
import com.valhalla.thor.data.backup.job.runLegacyAppExportTask
import com.valhalla.thor.data.backup.job.runLegacyArchiveTask
import com.valhalla.thor.data.backup.service.DataSyncService
import com.valhalla.thor.data.freezer.PrivilegeSweepClock
import com.valhalla.thor.data.freezer.PrivilegeSweepProcessGate
import com.valhalla.thor.data.freezer.PrivilegeSweepReconciler
import com.valhalla.thor.data.freezer.PrivilegeSweepWorker
import com.valhalla.thor.data.repository.RoomPrivilegeSweepStore
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.domain.model.BACKUP_BUNDLE_KEY
import com.valhalla.thor.domain.model.BACKUP_CLASSES_KEY
import com.valhalla.thor.domain.model.BACKUP_PACKAGE_KEY
import com.valhalla.thor.domain.model.BACKUP_SALT_KEY
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.EXPORT_FORMAT_KEY
import com.valhalla.thor.domain.model.EXPORT_LABEL_KEY
import com.valhalla.thor.domain.model.EXPORT_PACKAGE_KEY
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.RESTORE_CLASSES_KEY
import com.valhalla.thor.domain.model.RESTORE_PACKAGE_KEY
import com.valhalla.thor.domain.model.RESTORE_URI_KEY
import com.valhalla.thor.domain.model.SWEEP_REQUEST_ID_KEY
import com.valhalla.thor.domain.model.THOR_JOB_CHAIN
import com.valhalla.thor.domain.model.THOR_SWEEP_CHAIN
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class LegacyWorkManagerCutoverIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var store: PrivilegeSweepStore
    private lateinit var workerExecutor: ExecutorService
    private lateinit var taskExecutor: ExecutorService
    private lateinit var workManager: WorkManager
    private lateinit var workerFactory: CutoverWorkerFactory

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(
            "production Koin must not start for this integration test",
            GlobalContext.getOrNull()
        )
        assertEquals(TEST_APPLICATION_CLASS, context.applicationContext.javaClass.name)

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomPrivilegeSweepStore(database.privilegeSweepDao())
        workerExecutor = Executors.newSingleThreadExecutor()
        taskExecutor = Executors.newSingleThreadExecutor()
        workerFactory = CutoverWorkerFactory(context)

        val configuration = Configuration.Builder()
            .setExecutor(workerExecutor)
            .setTaskExecutor(taskExecutor)
            .setWorkerFactory(workerFactory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            configuration,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
    }

    @Suppress("RestrictedApi")
    @After
    fun tearDown() {
        runBlocking {
            val failures = mutableListOf<Throwable>()

            suspend fun cleanup(name: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += IllegalStateException("Cleanup failed: $name", failure)
                }
            }

            cleanup("cancel WorkManager") {
                if (::workManager.isInitialized) {
                    withContext(Dispatchers.IO) {
                        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
                    }
                }
            }
            cleanup("drain worker executor") {
                if (::workerExecutor.isInitialized) awaitIdle(workerExecutor)
            }
            cleanup("drain task executor") {
                if (::taskExecutor.isInitialized) awaitIdle(taskExecutor)
            }
            cleanup("close WorkManager database") {
                withTimeout(10.seconds) {
                    runInterruptible(Dispatchers.IO) {
                        WorkManagerTestInitHelper.closeWorkDatabase()
                    }
                }
            }
            cleanup("reset WorkManager delegate") { WorkManagerImpl.setDelegate(null) }
            cleanup("close app database") {
                if (::database.isInitialized) {
                    withTimeout(10.seconds) { runInterruptible(Dispatchers.IO) { database.close() } }
                }
            }
            cleanup("shutdown worker executor") {
                if (::workerExecutor.isInitialized) workerExecutor.shutdownNow()
            }
            cleanup("shutdown task executor") {
                if (::taskExecutor.isInitialized) taskExecutor.shutdownNow()
            }
            cleanup("await worker executor termination") {
                if (::workerExecutor.isInitialized) awaitTermination(workerExecutor, "worker")
            }
            cleanup("await task executor termination") {
                if (::taskExecutor.isInitialized) awaitTermination(taskExecutor, "task")
            }

            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test
    fun restoredReleasedExportMapsToSharedRunnerAndLegacySinkWithoutRoomRow() = runBlocking {
        val work = OneTimeWorkRequestBuilder<AppExportWorker>()
            .setInputData(
                workDataOf(
                    EXPORT_PACKAGE_KEY to PACKAGE_NAME,
                    EXPORT_FORMAT_KEY to BundleFormat.APK.name,
                    EXPORT_LABEL_KEY to APP_LABEL,
                )
            )
            .build()

        enqueueUnique(THOR_JOB_CHAIN, work)
        val terminal = awaitTerminal(work.id)
        val request = withTimeout(10.seconds) { workerFactory.exportRequest.await() }

        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertEquals(AppExportWorker::class.java.name, workerFactory.workerClasses[work.id])
        assertEquals(work.id, request.taskId)
        assertEquals(DataTaskKind.APP_EXPORT, request.kind)
        assertEquals(PACKAGE_NAME, request.item.packageName)
        assertEquals(APP_LABEL, request.item.displayLabel)
        assertNull(database.dataTaskDao().loadTask(work.id.toString()))
    }

    @Test
    fun restoredReleasedArchivesWithoutProcessKeysFailClosed() = runBlocking {
        val backup = OneTimeWorkRequestBuilder<ArchiveBackupWorker>()
            .setInputData(
                workDataOf(
                    BACKUP_PACKAGE_KEY to PACKAGE_NAME,
                    BACKUP_CLASSES_KEY to arrayOf(DataClass.CE.id),
                    BACKUP_BUNDLE_KEY to false,
                    BACKUP_SALT_KEY to Base64.getEncoder().encodeToString(ByteArray(16)),
                )
            )
            .build()
        val restore = OneTimeWorkRequestBuilder<ArchiveRestoreWorker>()
            .setInputData(
                workDataOf(
                    RESTORE_URI_KEY to "content://test/archive.thorbak",
                    RESTORE_PACKAGE_KEY to PACKAGE_NAME,
                    RESTORE_CLASSES_KEY to arrayOf(DataClass.CE.id),
                )
            )
            .build()

        enqueueUnique(THOR_JOB_CHAIN, backup)
        val backupResult = awaitTerminal(backup.id)
        enqueueUnique(THOR_JOB_CHAIN, restore)
        val restoreResult = awaitTerminal(restore.id)

        assertEquals(WorkInfo.State.FAILED, backupResult.state)
        assertEquals(WorkInfo.State.FAILED, restoreResult.state)
        assertTrue(backupResult.outputData.getString(JOB_ERROR_KEY).orEmpty().contains("key"))
        assertTrue(restoreResult.outputData.getString(JOB_ERROR_KEY).orEmpty().contains("key"))
        assertEquals(0, workerFactory.archiveRunnerCalls)
        assertEquals(ArchiveBackupWorker::class.java.name, workerFactory.workerClasses[backup.id])
        assertEquals(ArchiveRestoreWorker::class.java.name, workerFactory.workerClasses[restore.id])
        assertNull(database.dataTaskDao().loadTask(backup.id.toString()))
        assertNull(database.dataTaskDao().loadTask(restore.id.toString()))
    }

    @Test
    fun reconstructedPrivilegeSweepWorkerIsATerminalNoMutationTombstone() = runBlocking {
        val requestId = UUID.randomUUID()
        val work = OneTimeWorkRequestBuilder<PrivilegeSweepWorker>()
            .setInputData(workDataOf(SWEEP_REQUEST_ID_KEY to requestId.toString()))
            .build()
        val created = store.createOrFindEquivalent(
            NewPrivilegeSweepSnapshot(
                requestId = requestId,
                workId = work.id,
                operation = PrivilegeSweepOperation.CLEAR_CACHE,
                freezerMode = null,
                userId = 0,
                source = PrivilegeSweepSource.SETTINGS,
                createdAtEpochMs = NOW,
                targets = listOf(PACKAGE_NAME),
            )
        )
        assertTrue(created is SweepCreateResult.Created)
        val before = checkNotNull(store.load(requestId))

        enqueueUnique(THOR_SWEEP_CHAIN, work)
        val terminal = awaitTerminal(work.id)

        assertEquals(WorkInfo.State.FAILED, terminal.state)
        assertEquals(PrivilegeSweepWorker::class.java.name, workerFactory.workerClasses[work.id])
        assertEquals(before, store.load(requestId))
    }

    @Test
    fun startupPruningDoesNotStartEitherForegroundService() = runBlocking {
        val reconciler = PrivilegeSweepReconciler(
            store = store,
            clock = TestClock,
            gate = PrivilegeSweepProcessGate(),
        )

        reconciler.pruneRetained()

        assertFalse(isServiceRunning(DataSyncService::class.java.name))
        assertFalse(isServiceRunning("com.valhalla.thor.data.freezer.PrivilegeSweepService"))
    }

    private suspend fun enqueueUnique(chain: String, work: OneTimeWorkRequest) {
        withContext(Dispatchers.IO) {
            workManager.beginUniqueWork(chain, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
                .enqueue()
                .result
                .get(10, TimeUnit.SECONDS)
        }
    }

    private suspend fun awaitTerminal(workId: UUID): WorkInfo = withTimeoutOrNull(10.seconds) {
        var terminal: WorkInfo? = null
        while (terminal == null) {
            val current = withContext(Dispatchers.IO) {
                workManager.getWorkInfoById(workId).get(2, TimeUnit.SECONDS)
            }
            terminal = current?.takeIf { it.state.isFinished }
            if (terminal == null) kotlinx.coroutines.yield()
        }
        terminal
    } ?: error("Timed out waiting for terminal work $workId")

    @Suppress("DEPRECATION")
    private fun isServiceRunning(className: String): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == className }

    private fun awaitIdle(executor: ExecutorService) {
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun awaitTermination(executor: ExecutorService, name: String) {
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
            "$name executor did not terminate"
        }
    }

    private class CutoverWorkerFactory(
        context: Context,
    ) : WorkerFactory() {
        val workerClasses = ConcurrentHashMap<UUID, String>()
        val exportRequest = CompletableDeferred<DataTaskExecutionRequest>()
        var archiveRunnerCalls = 0

        private val notifications = ThorJobNotifications(context)
        private val registry = JobRegistry()
        private val sheetTargets = JobSheetTargets()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? {
            workerClasses[workerParameters.id] = workerClassName
            return when (workerClassName) {
                AppExportWorker::class.java.name -> ExportCompatibilityWorker(
                    appContext,
                    workerParameters,
                    exportRequest,
                )

                ArchiveBackupWorker::class.java.name -> ArchiveCompatibilityWorker(
                    appContext,
                    workerParameters,
                    DataTaskKind.ARCHIVE_BACKUP,
                    ::noteArchiveRunnerCall,
                )

                ArchiveRestoreWorker::class.java.name -> ArchiveCompatibilityWorker(
                    appContext,
                    workerParameters,
                    DataTaskKind.ARCHIVE_RESTORE,
                    ::noteArchiveRunnerCall,
                )

                PrivilegeSweepWorker::class.java.name -> PrivilegeSweepWorker(
                    appContext = appContext,
                    params = workerParameters,
                    notifications = notifications,
                    registry = registry,
                    sheetTargets = sheetTargets,
                )

                else -> null
            }
        }

        private fun noteArchiveRunnerCall() {
            archiveRunnerCalls++
        }
    }

    private class ExportCompatibilityWorker(
        appContext: Context,
        params: WorkerParameters,
        private val requestCapture: CompletableDeferred<DataTaskExecutionRequest>,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = runLegacyAppExportTask(
            taskId = id,
            decodedRequest = decodeLegacyAppExportRequest(inputData.keyValueMap),
            runAttemptCount = runAttemptCount,
            invalidRequestReason = "invalid released export request",
            runner = object : DataTaskRunner {
                override val kind = DataTaskKind.APP_EXPORT

                override suspend fun run(
                    request: DataTaskExecutionRequest,
                    checkpoints: DataTaskCheckpointSink,
                ): DataTaskRunOutcome {
                    requestCapture.complete(request)
                    return successfulOutcome()
                }
            },
            checkpoints = { DataTaskSinkWrite.APPLIED },
            results = LegacyWorkerResultSink(DataTaskKind.APP_EXPORT),
        )
    }

    private class ArchiveCompatibilityWorker(
        appContext: Context,
        params: WorkerParameters,
        private val kind: DataTaskKind,
        private val runnerCalled: () -> Unit,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = when (kind) {
            DataTaskKind.ARCHIVE_BACKUP -> runLegacyArchiveTask(
                taskId = id,
                decodedRequest = decodeLegacyArchiveBackupRequest(inputData.keyValueMap),
                invalidRequestReason = "invalid released backup request",
                requestFactory = { _, _, _ -> error("missing key must short-circuit") },
                takeKey = { null },
                runner = unreachableRunner(kind),
                checkpoints = { DataTaskSinkWrite.APPLIED },
                results = LegacyWorkerResultSink(kind),
                missingKeyReason = "this backup's key is no longer in memory — start it again",
            )

            DataTaskKind.ARCHIVE_RESTORE -> runLegacyArchiveTask(
                taskId = id,
                decodedRequest = decodeLegacyArchiveRestoreRequest(inputData.keyValueMap),
                invalidRequestReason = "invalid released restore request",
                requestFactory = { _, _, _ -> error("missing key must short-circuit") },
                takeKey = { null },
                runner = unreachableRunner(kind),
                checkpoints = { DataTaskSinkWrite.APPLIED },
                results = LegacyWorkerResultSink(kind),
                missingKeyReason = "this restore's key is no longer in memory — start it again",
            )

            else -> error("unsupported archive kind $kind")
        }

        private fun unreachableRunner(expectedKind: DataTaskKind) = object : DataTaskRunner {
            override val kind = expectedKind

            override suspend fun run(
                request: DataTaskExecutionRequest,
                checkpoints: DataTaskCheckpointSink,
            ): DataTaskRunOutcome {
                runnerCalled()
                error("archive runner must not run without its process-local key")
            }
        }
    }

    private companion object {
        const val TEST_APPLICATION_CLASS = "com.valhalla.thor.ThorTestApplication"
        const val PACKAGE_NAME = "com.example.restored"
        const val APP_LABEL = "Restored app"
        const val NOW = 1_000_000L

        val TestClock = PrivilegeSweepClock { NOW }

        fun successfulOutcome(): DataTaskRunOutcome = DataTaskRunOutcome.ItemCompleted(
            DataTaskItemResult(
                terminalState = DataTaskItemTerminalState.SUCCEEDED,
                resultCode = DataTaskResultCode("LEGACY_EXPORT_COMPLETED"),
                warnings = emptyList(),
                outputs = emptyList(),
                finishedAtEpochMs = NOW,
            )
        )
    }
}
