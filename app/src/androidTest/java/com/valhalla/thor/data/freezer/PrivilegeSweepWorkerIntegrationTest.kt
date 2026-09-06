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
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
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
    private lateinit var workerExecutor: ExecutorService
    private lateinit var taskExecutor: ExecutorService
    private lateinit var workManager: WorkManager
    private lateinit var testDriver: TestDriver

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(
            "production Koin must not start for this integration test",
            GlobalContext.getOrNull()
        )
        assertEquals(
            TEST_APPLICATION_CLASS,
            context.applicationContext.javaClass.name,
        )
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomPrivilegeSweepStore(database.privilegeSweepDao())
        workerExecutor = Executors.newSingleThreadExecutor()
        taskExecutor = Executors.newSingleThreadExecutor()

        val configuration = Configuration.Builder()
            .setExecutor(workerExecutor)
            .setTaskExecutor(taskExecutor)
            .setWorkerFactory(SweepWorkerFactory(context))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            configuration,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
        testDriver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
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
    fun workerReconstructsAsTerminalTombstoneWithoutMutationOrForegroundService() = runBlocking {
        val sweep = delayedSweep()
        val work = sweep.work
        val targets = randomTargets()
        persist(sweep, targets)
        val before = checkNotNull(store.load(sweep.requestId))
        enqueue(work)

        assertEquals(setOf(SWEEP_REQUEST_ID_KEY), sweep.inputKeys)
        testDriver.setInitialDelayMet(work.id)
        awaitWork(work.id, WorkInfo.State.FAILED)

        assertEquals(before, store.load(sweep.requestId))
        assertFalse(isSystemForegroundServiceRunning())
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
        val created = store.createOrFindEquivalent(
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

    private suspend fun awaitWork(
        workId: UUID,
        state: WorkInfo.State,
    ): WorkInfo = withTimeoutOrNull(10.seconds) {
        workManager.getWorkInfoByIdFlow(workId)
            .filterNotNull()
            .first { it.state == state }
    } ?: error("Timed out waiting for work state $state; current=${currentWorkState(workId)}")

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

    private class SweepWorkerFactory(
        context: Context,
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
            return PrivilegeSweepWorker(
                appContext = appContext,
                params = workerParameters,
                notifications = notifications,
                registry = registry,
                sheetTargets = sheetTargets,
            )
        }
    }

    private companion object {
        const val TEST_APPLICATION_CLASS = "com.valhalla.thor.ThorTestApplication"
        const val SYSTEM_FOREGROUND_SERVICE =
            "androidx.work.impl.foreground.SystemForegroundService"
    }
}
