// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.data.backup.job.JobSheetTargets
import com.valhalla.thor.data.backup.job.ThorJobNotifications
import com.valhalla.thor.data.repository.RoomPrivilegeSweepStore
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.SWEEP_REQUEST_ID_KEY
import com.valhalla.thor.domain.model.THOR_SWEEP_CHAIN
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

@RunWith(AndroidJUnit4::class)
class PrivilegeSweepWorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var store: PrivilegeSweepStore
    private lateinit var gate: PrivilegeSweepProcessGate
    private lateinit var executor: ControlledItemExecutor
    private lateinit var workerExecutor: ExecutorService
    private lateinit var taskExecutor: ExecutorService
    private lateinit var workManager: WorkManager
    private lateinit var testDriver: TestDriver
    private lateinit var queueCanceller: SweepQueueCanceller

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull("production Koin must not start for this integration test", GlobalContext.getOrNull())
        assertEquals(
            TEST_APPLICATION_CLASS,
            context.applicationContext.javaClass.name,
        )
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomPrivilegeSweepStore(database.privilegeSweepDao())
        gate = PrivilegeSweepProcessGate()
        executor = ControlledItemExecutor()
        workerExecutor = Executors.newSingleThreadExecutor()
        taskExecutor = Executors.newSingleThreadExecutor()

        val configuration = Configuration.Builder()
            .setExecutor(workerExecutor)
            .setTaskExecutor(taskExecutor)
            .setWorkerFactory(SweepWorkerFactory(context, database, gate, executor))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            configuration,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
        testDriver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        queueCanceller = SweepQueueCanceller(
            store = store,
            clock = TestClock,
            gate = gate,
            workManager = WorkManagerSweepQueueWorkManager(context),
        )
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

            cleanup("release item executor") { if (::executor.isInitialized) executor.release() }
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
            cleanup("close sweep database") {
                if (::database.isInitialized) {
                    withTimeout(10.seconds) {
                        runInterruptible(Dispatchers.IO) { database.close() }
                    }
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
    fun workerReconstructsRoomRequestAndRunsWithoutForegroundService() = runBlocking {
        executor.block()
        val sweep = delayedSweep()
        val work = sweep.work
        val targets = randomTargets()
        persist(sweep, targets)
        enqueue(work)

        assertEquals(setOf(SWEEP_REQUEST_ID_KEY), sweep.inputKeys)
        testDriver.setInitialDelayMet(work.id)
        val firstCall = awaitFirstCall(work.id)
        awaitWork(work.id, WorkInfo.State.RUNNING)

        assertEquals(targets.first(), firstCall.packageName)
        assertEquals(work.id, firstCall.snapshot.workId)
        assertEquals(sweep.requestId, firstCall.snapshot.requestId)
        assertFalse(isSystemForegroundServiceRunning())

        executor.release()
        awaitWork(work.id, WorkInfo.State.SUCCEEDED)
        val completed = awaitTerminal(sweep.requestId, StoredSweepTerminal.SUCCEEDED)

        assertEquals(targets, executor.calls.map(ItemCall::packageName))
        assertEquals(2, completed.succeeded)
        assertEquals(0, completed.failed)
        assertEquals(0, completed.busy)
        assertEquals(0, completed.unresolved)
    }

    @Test
    fun queueCancellationBeforeStartLeavesEveryTargetUnresolved() = runBlocking {
        val sweep = delayedSweep()
        val work = sweep.work
        val targets = randomTargets()
        persist(sweep, targets)
        enqueue(work)
        awaitWork(work.id, WorkInfo.State.ENQUEUED)

        queueCanceller.cancelQueue()

        val cancelled = awaitTerminal(sweep.requestId, StoredSweepTerminal.CANCELLED)
        awaitWork(work.id, WorkInfo.State.CANCELLED)
        assertTrue(executor.calls.isEmpty())
        assertEquals(targets.size, cancelled.unresolved)
    }

    @Test
    fun queueCancellationWhileRunningPreservesCompletedAndUnresolvedCounts() = runBlocking {
        executor.block()
        val sweep = delayedSweep()
        val work = sweep.work
        val targets = randomTargets()
        persist(sweep, targets)
        enqueue(work)
        testDriver.setInitialDelayMet(work.id)
        awaitFirstCall(work.id)
        awaitWork(work.id, WorkInfo.State.RUNNING)

        queueCanceller.cancelQueue()

        val cancelled = awaitTerminal(sweep.requestId, StoredSweepTerminal.CANCELLED)
        awaitWork(work.id, WorkInfo.State.CANCELLED)
        assertEquals(listOf(targets.first()), executor.calls.map(ItemCall::packageName))
        assertEquals(0, cancelled.succeeded)
        assertEquals(targets.size, cancelled.unresolved)
    }

    private fun delayedSweep(): TestSweep {
        val requestId = UUID.randomUUID()
        val input = workDataOf(SWEEP_REQUEST_ID_KEY to requestId.toString())
        val work = OneTimeWorkRequestBuilder<PrivilegeSweepWorker>()
            .setId(UUID.randomUUID())
            .setInputData(input)
            .setInitialDelay(Duration.ofDays(1))
            .build()
        return TestSweep(requestId, work, input.keyValueMap.keys)
    }

    private suspend fun persist(
        sweep: TestSweep,
        targets: List<String>,
    ) {
        val created = gate.serialized {
            store.createOrFindEquivalent(
                NewPrivilegeSweepSnapshot(
                    requestId = sweep.requestId,
                    workId = sweep.work.id,
                    operation = PrivilegeSweepOperation.CLEAR_CACHE,
                    freezerMode = null,
                    userId = 0,
                    source = PrivilegeSweepSource.SETTINGS,
                    createdAtEpochMs = System.currentTimeMillis(),
                    targets = targets,
                )
            )
        }
        assertTrue(created is SweepCreateResult.Created)
    }

    private suspend fun enqueue(work: OneTimeWorkRequest) {
        withContext(Dispatchers.IO) {
            workManager.beginUniqueWork(
                THOR_SWEEP_CHAIN,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work,
            ).enqueue().result.get()
        }
    }

    private suspend fun awaitFirstCall(workId: UUID): ItemCall =
        withTimeoutOrNull(10.seconds) { executor.firstCall.await() }
            ?: error("Timed out waiting for item execution; work=${currentWorkState(workId)}")

    private suspend fun awaitWork(
        workId: UUID,
        state: WorkInfo.State,
    ): WorkInfo = withTimeoutOrNull(10.seconds) {
        workManager.getWorkInfoByIdFlow(workId)
            .filterNotNull()
            .first { it.state == state }
    } ?: error("Timed out waiting for work state $state; current=${currentWorkState(workId)}")

    private suspend fun awaitTerminal(
        requestId: UUID,
        terminal: StoredSweepTerminal,
    ): StoredPrivilegeSweep = withTimeoutOrNull(10.seconds) {
        store.observe(requestId)
            .filterNotNull()
            .first { it.terminalState == terminal }
    } ?: error("Timed out waiting for Room terminal $terminal; current=${store.load(requestId)}")

    private suspend fun currentWorkState(workId: UUID): WorkInfo.State? =
        withContext(Dispatchers.IO) {
            workManager.getWorkInfoById(workId).get(2, TimeUnit.SECONDS)?.state
        }

    @Suppress("DEPRECATION")
    private fun isSystemForegroundServiceRunning(): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == SYSTEM_FOREGROUND_SERVICE
        }
    }

    private fun randomTargets(): List<String> =
        List(2) { randomPackage() }.sorted()

    private fun randomPackage(): String =
        "test.${UUID.randomUUID().toString().replace('-', '.')}"

    private fun awaitIdle(executor: ExecutorService) {
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun awaitTermination(executor: ExecutorService, name: String) {
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
            "$name executor did not terminate"
        }
    }

    private data class TestSweep(
        val requestId: UUID,
        val work: OneTimeWorkRequest,
        val inputKeys: Set<String>,
    )

    private data class ItemCall(
        val snapshot: StoredPrivilegeSweep,
        val packageName: String,
    )

    private class ControlledItemExecutor : PrivilegeSweepItemExecutor {
        val calls = CopyOnWriteArrayList<ItemCall>()
        val firstCall = CompletableDeferred<ItemCall>()
        private var shouldBlock = false
        private val released = CompletableDeferred<Unit>()

        fun block() {
            shouldBlock = true
        }

        fun release() {
            released.complete(Unit)
        }

        override suspend fun execute(
            snapshot: StoredPrivilegeSweep,
            packageName: String,
        ): SweepAttemptOutcome {
            val call = ItemCall(snapshot, packageName)
            calls += call
            firstCall.complete(call)
            if (shouldBlock) released.await()
            return SweepAttemptOutcome.SUCCEEDED
        }
    }

    private class SweepWorkerFactory(
        context: Context,
        private val database: AppDatabase,
        private val gate: PrivilegeSweepProcessGate,
        private val executor: PrivilegeSweepItemExecutor,
    ) : WorkerFactory() {
        private val notifications = ThorJobNotifications(context)
        private val registry = JobRegistry()
        private val sheetTargets = JobSheetTargets()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? {
            if (workerClassName != PrivilegeSweepWorker::class.java.name) return null
            val runner = PrivilegeSweepRunner(
                store = RoomPrivilegeSweepStore(database.privilegeSweepDao()),
                executor = executor,
                clock = TestClock,
                gate = gate,
                ioDispatcher = Dispatchers.IO,
            )
            return PrivilegeSweepWorker(
                appContext = appContext,
                params = workerParameters,
                notifications = notifications,
                registry = registry,
                runner = runner,
                sheetTargets = sheetTargets,
            )
        }
    }

    private object TestClock : PrivilegeSweepClock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    private companion object {
        const val TEST_APPLICATION_CLASS = "com.valhalla.thor.ThorTestApplication"
        const val SYSTEM_FOREGROUND_SERVICE =
            "androidx.work.impl.foreground.SystemForegroundService"
    }
}
