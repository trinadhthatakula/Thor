// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.backup.job

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.data.source.local.room.DataTaskCancellationDecision
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.presentation.share.ReadyShareAccessLock
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.*
import com.valhalla.thor.domain.usecase.*
import com.valhalla.thor.presentation.FakeAppRepository
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SharePrepareRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getCacheDir(): File = File(temporary.root, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }
    private val db by lazy {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
    }
    private val store by lazy { DataTaskStore(db.dataTaskDao()) }
    private val packages = DefaultPackageOperationCoordinator()
    private val access = ReadyShareAccessLock()
    private val cleanup by lazy { SharePrepareCleanup(context, db.dataTaskDao(), access, Dispatchers.Unconfined) }
    private var beforeBuild: suspend (AppInfo) -> Unit = {}
    private val reads = mutableListOf<String>()
    private val apps = FakeAppRepository(listOf(
        AppInfo(packageName = "com.example.one", appName = "One", versionName = "1"),
        AppInfo(packageName = "com.example.two", appName = "Two", versionName = "1", splitPublicSourceDirs = listOf("config.apk")),
    ))
    private val builder = object : AppBundleBuilder {
        override suspend fun build(appInfo: AppInfo, cacheSubDir: String, format: BundleFormat, fileName: String?, execution: PrivilegeExecutionContext): Result<File> {
            assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.BUNDLE_READ),
                packages.withPackageLease(appInfo.packageName, PackageOperationOwner.UNINSTALL, Duration.ZERO) { error("mutation entered") })
            beforeBuild(appInfo)
            reads += appInfo.packageName
            return Result.success(File(context.cacheDir, "$cacheSubDir/${appInfo.packageName}/$fileName").apply {
                parentFile!!.mkdirs(); writeText("bundle ${appInfo.packageName}")
            })
        }
    }

    @After fun close() { db.close() }

    @Test fun `real runtime claims share prepares mixed outputs and only then posts foreground action`() = runTest {
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        val runtime = runtime()
        completeNext(runtime)
        assertEquals(DataTaskState.QUEUED, store.loadTask(id)!!.state)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        completeNext(runtime)
        val task = store.loadTask(id)!!
        assertEquals(DataTaskState.READY, task.state)
        assertEquals(listOf("com.example.one", "com.example.two"), reads)
        assertEquals(listOf("apk", "apks"), task.outputs.map { it.displayName.substringAfterLast('.') })
        assertTrue(task.outputs.all { File(context.cacheDir, it.privateRelativePath!!).isFile })
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(1, notifications.size)
        assertNotNull(notifications.single().notification.contentIntent)
    }

    @Test fun `resumed share rebuilds a missing prepared item before pending items`() = runTest {
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        completeNext(runtime())
        val prepared = store.loadTask(id)!!.outputs.single()
        assertTrue(File(context.cacheDir, prepared.privateRelativePath!!).delete())
        val recreated = runtime()
        val claim = recreated.claimNext("new-session", UUID.randomUUID().toString())!!
        assertEquals("missing prepared item must be rebuilt", 0, claim.item!!.ordinal)
        assertEquals(DataTaskSinkWrite.APPLIED, recreated.persistOutcome(claim,
            recreated.executeClaim(claim, recreated.checkpointSink(claim))))
        completeNext(recreated)
        assertEquals(DataTaskState.READY, store.loadTask(id)!!.state)
        assertEquals(listOf("com.example.one", "com.example.one", "com.example.two"), reads)
        assertTrue(store.loadTask(id)!!.outputs.all { File(context.cacheDir, it.privateRelativePath!!).isFile })
    }

    @Test fun `cancel after preparation removes unpublished bytes without deleting another ready task`() = runTest {
        val keepId = UUID.randomUUID()
        store.insertShare(keepId, AppShareRequest(listOf(selection().targets.first())), System.currentTimeMillis())
        val runtime = runtime()
        completeNext(runtime)
        val retained = store.loadTask(keepId)!!.outputs.single()
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        val claim = runtime.claimNext("session", UUID.randomUUID().toString())!!
        val result = runtime.executeClaim(claim, runtime.checkpointSink(claim)) as DataTaskRunOutcome.ItemCompleted
        val unpublished = File(context.cacheDir, result.result.outputs.single().privateRelativePath!!)
        assertTrue(unpublished.isFile)
        assertEquals(DataTaskSinkWrite.APPLIED, runtime.persistOutcome(claim, DataTaskRunOutcome.Cancelled))
        runtime.cleanupClaim(claim)
        assertFalse(unpublished.exists())
        assertTrue(File(context.cacheDir, retained.privateRelativePath!!).isFile)
    }

    @Test fun `process startup cleans abandoned work but preserves recoverable final publication`() = runTest {
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        val work = File(context.cacheDir, "share_work/item-$id-0/com.example.one/part").apply { parentFile!!.mkdirs(); writeText("partial") }
        val ready = File(context.cacheDir, "share_ready/item-$id-0/com.example.one/final.apk").apply { parentFile!!.mkdirs(); writeText("complete") }
        val legacy = File(context.cacheDir, "share_temp/legacy.apk").apply { parentFile!!.mkdirs(); writeText("legacy") }
        SharePrepareCleanup(context, db.dataTaskDao(), com.valhalla.thor.presentation.share.ReadyShareAccessLock(), Dispatchers.Unconfined)
            .sweepAfterProcessStart()
        assertFalse(work.exists())
        assertTrue(ready.isFile)
        assertTrue(legacy.isFile)
    }

    @Test fun `missing output reconciliation refuses a new owner and stale output sets`() = runTest {
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        completeNext(runtime())
        val snapshot = store.loadTask(id)!!
        val output = snapshot.outputs.single().outputId.toString()
        assertFalse(db.dataTaskDao().requeueMissingShareOutputs(snapshot, listOf(UUID.randomUUID().toString()), System.currentTimeMillis()))
        val claim = runtime().claimNext("next-session", UUID.randomUUID().toString())!!
        assertFalse(db.dataTaskDao().requeueMissingShareOutputs(snapshot, listOf(output), System.currentTimeMillis()))
        assertEquals(DataTaskItemState.SUCCEEDED, store.loadTask(id)!!.items.first().state)
        assertEquals(1, claim.item!!.ordinal)
    }

    @Test fun `cancelled share deletes already persisted output and expires only its output row`() = runTest {
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        completeNext(runtime())
        val output = store.loadTask(id)!!.outputs.single()
        val file = File(context.cacheDir, output.privateRelativePath!!)
        assertTrue(file.isFile)
        db.dataTaskDao().requestCancellation(id.toString(), System.currentTimeMillis())
        val cleanup = SharePrepareCleanup(context, db.dataTaskDao(), com.valhalla.thor.presentation.share.ReadyShareAccessLock(), Dispatchers.Unconfined)
        cleanup.cleanup(id)
        val settled = store.loadTask(id)!!
        assertEquals(DataTaskState.CANCELLED, settled.state)
        assertEquals(DataTaskOutputState.EXPIRED, settled.outputs.single().state)
        assertFalse(file.exists())
        cleanup.cleanup(id)
        assertEquals(settled, store.loadTask(id))
    }

    @Test fun `terminal cleanup refuses a symlink tree and keeps its output retryable`() = runTest {
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        completeNext(runtime())
        val output = store.loadTask(id)!!.outputs.single()
        val file = File(context.cacheDir, output.privateRelativePath!!)
        assertTrue(file.delete())
        assertTrue(file.parentFile!!.delete())
        val outside = temporary.newFolder("outside")
        val outsideFile = File(outside, output.displayName).apply { writeText("unrelated") }
        java.nio.file.Files.createSymbolicLink(file.parentFile!!.toPath(), outside.toPath())
        db.dataTaskDao().requestCancellation(id.toString(), System.currentTimeMillis())
        val expected = store.loadTask(id)!!
        SharePrepareCleanup(context, db.dataTaskDao(), com.valhalla.thor.presentation.share.ReadyShareAccessLock(), Dispatchers.Unconfined).cleanup(id)
        assertEquals(expected, store.loadTask(id))
        assertEquals("unrelated", outsideFile.readText())
        assertTrue(java.nio.file.Files.isSymbolicLink(file.parentFile!!.toPath()))
    }

    @Test fun `runtime preserves successful output when a later item fails and posts partial ready`() = runTest {
        val id = UUID.randomUUID()
        store.insertShare(id, selection(), System.currentTimeMillis())
        val runtime = runtime()
        completeNext(runtime)
        apps.apps.value = apps.apps.value.take(1)
        val claim = runtime.claimNext("session", UUID.randomUUID().toString())!!
        val result = runtime.executeClaim(claim, runtime.checkpointSink(claim)) as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.FAILED, result.result.terminalState)
        assertEquals(DataTaskSinkWrite.APPLIED, runtime.persistOutcome(claim, result))
        runtime.cleanupClaim(claim)
        val ready = store.loadTask(id)!!
        assertEquals(DataTaskState.READY_PARTIAL, ready.state)
        assertEquals(listOf(DataTaskItemState.SUCCEEDED, DataTaskItemState.FAILED), ready.items.map { it.state })
        assertTrue(File(context.cacheDir, ready.outputs.single().privateRelativePath!!).isFile)
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test fun `timeout child completion then queue cancel reclaims persisted share output`() = runTest {
        val (queued, retained) = prepareQueuedShareWithUnrelatedReadyTask()
        val owners = DataTaskOwnerRegistry()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        beforeBuild = { app ->
            assertEquals("com.example.two", app.packageName)
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                exited.complete(Unit)
            }
        }
        val coordinator = DataSyncCoordinator(StandardTestDispatcher(testScheduler), owners, runtime())
        coordinator.wake(onDrained = { error("timeout must not report a completed drain") })
        try {
            entered.await()
            val running = store.loadTask(queued.taskId)!!
            assertEquals(DataTaskState.RUNNING, running.state)
            val claimToken = coordinator.lastClaimTokenForTest!!
            assertTrue(owners.isLive(queued.taskId, claimToken))
            coordinator.stopClaimsAndInterrupt()
            assertTrue("the timed-out child has completed", exited.isCompleted)
            assertFalse(coordinator.hasDrainJobForTest)
            assertFalse(owners.isLive(queued.taskId, claimToken))
            assertFalse(store.hasClaimTokens(claimToken, null))
            assertQueuedAndResumable(queued)

            assertTrue(cancellationCoordinator(owners).cancel(queued.taskId) is DataTaskCancellationDecision.Settled)

            assertCancelledAndReclaimed(queued, retained)
        } finally {
            coordinator.stopClaimsAndInterrupt()
        }
    }

    @Test fun `between item queue cancel reclaims persisted share output`() = runTest {
        val (queued, retained) = prepareQueuedShareWithUnrelatedReadyTask()
        assertQueuedAndResumable(queued)

        assertTrue(cancellationCoordinator().cancel(queued.taskId) is DataTaskCancellationDecision.Settled)

        assertCancelledAndReclaimed(queued, retained)
    }

    @Test fun `stale claimed queue cancel reclaims persisted share output`() = runTest {
        val (queued, retained) = prepareQueuedShareWithUnrelatedReadyTask()
        val claim = runtime().claimNext("dead-session", "dead-claim")!!
        assertEquals(1, claim.item!!.ordinal)
        assertEquals(DataTaskState.RUNNING, store.loadTask(queued.taskId)!!.state)

        assertTrue(cancellationCoordinator().cancel(queued.taskId) is DataTaskCancellationDecision.InterruptActive)

        assertCancelledAndReclaimed(queued, retained)
    }

    @Test fun `startup recovery reclaims owned cancel requested share after startup sweep refuses it`() = runTest {
        val (queued, retained) = prepareQueuedShareWithUnrelatedReadyTask()
        val claim = runtime().claimNext("dead-session", "dead-claim")!!
        assertEquals(1, claim.item!!.ordinal)
        assertTrue(store.requestCancellation(queued.taskId, System.currentTimeMillis()) is DataTaskCancellationDecision.InterruptActive)
        val owned = store.loadTask(queued.taskId)!!
        assertEquals(DataTaskState.CANCEL_REQUESTED, owned.state)
        assertTrue(store.hasClaimTokens(claim.claimToken, claim.item.claimToken))
        cleanup.sweepAfterProcessStart()
        assertEquals("startup must leave the owned task alone", owned, store.loadTask(queued.taskId))
        assertTrue(File(context.cacheDir, queued.outputs.single().privateRelativePath!!).isFile)
        val drained = CompletableDeferred<Unit>()
        val coordinator = DataSyncCoordinator(StandardTestDispatcher(testScheduler), DataTaskOwnerRegistry(), runtime())
        coordinator.wake(onDrained = { drained.complete(Unit) })
        try {
            drained.await()

            assertCancelledAndReclaimed(queued, retained)
            assertEquals("recovery must not rerun either package", listOf("com.example.one", "com.example.one"), reads)
        } finally {
            coordinator.stopClaimsAndInterrupt()
        }
    }

    @Test fun `queue cancellation cleanup survives caller cancellation while awaiting share access`() = runTest {
        val (queued, retained) = prepareQueuedShareWithUnrelatedReadyTask()
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            access.withLock {
                locked.complete(Unit)
                release.await()
            }
        }
        locked.await()
        val cancellation = launch { cancellationCoordinator().cancel(queued.taskId) }
        try {
            runCurrent()
            assertEquals(DataTaskState.CANCELLED, store.loadTask(queued.taskId)!!.state)
            cancellation.cancel()
            release.complete(Unit)
            cancellation.join()

            assertCancelledAndReclaimed(queued, retained)
        } finally {
            release.complete(Unit)
            holder.join()
            cancellation.join()
        }
    }

    @Test fun `queue cancellation cleanup bounds lock waiting and leaves retryable output`() = runTest {
        val (queued, retained) = prepareQueuedShareWithUnrelatedReadyTask()
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            access.withLock {
                locked.complete(Unit)
                release.await()
            }
        }
        locked.await()
        val cancellation = async { runCatching { cancellationCoordinator().cancel(queued.taskId) } }
        try {
            runCurrent()
            assertEquals(DataTaskState.CANCELLED, store.loadTask(queued.taskId)!!.state)
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue("cleanup must not wait indefinitely for a share handoff", cancellation.isCompleted)
            assertTrue(cancellation.await().exceptionOrNull() is TimeoutCancellationException)
            assertEquals(DataTaskOutputState.READY, store.loadTask(queued.taskId)!!.outputs.single().state)
            assertTrue(File(context.cacheDir, queued.outputs.single().privateRelativePath!!).isFile)
            assertEquals(retained, store.loadTask(retained.taskId))
        } finally {
            release.complete(Unit)
            holder.join()
            cancellation.join()
        }
        cancellationCoordinator().cancel(queued.taskId)
        assertCancelledAndReclaimed(queued, retained)
    }

    private suspend fun prepareQueuedShareWithUnrelatedReadyTask(): Pair<DataTaskSnapshot, DataTaskSnapshot> {
        val retainedId = UUID.randomUUID()
        store.insertShare(retainedId, AppShareRequest(listOf(selection().targets.first())), System.currentTimeMillis())
        completeNext(runtime())
        val queuedId = UUID.randomUUID()
        store.insertShare(queuedId, selection(), System.currentTimeMillis())
        completeNext(runtime())
        return store.loadTask(queuedId)!! to store.loadTask(retainedId)!!
    }

    private suspend fun assertQueuedAndResumable(expected: DataTaskSnapshot) {
        val queued = store.loadTask(expected.taskId)!!
        assertEquals(DataTaskState.QUEUED, queued.state)
        assertEquals(listOf(DataTaskItemState.SUCCEEDED, DataTaskItemState.PENDING), queued.items.map { it.state })
        assertEquals(expected.outputs, queued.outputs)
        assertEquals(DataTaskOutputState.READY, queued.outputs.single().state)
        assertTrue(File(context.cacheDir, queued.outputs.single().privateRelativePath!!).isFile)
    }

    private suspend fun assertCancelledAndReclaimed(expected: DataTaskSnapshot, retained: DataTaskSnapshot) {
        val cancelled = store.loadTask(expected.taskId)!!
        assertEquals(DataTaskState.CANCELLED, cancelled.state)
        assertEquals(cancelled, db.dataTaskDao().unownedShareCleanupSnapshot(expected.taskId.toString()))
        assertEquals(expected.outputs.map { it.outputId }, cancelled.outputs.map { it.outputId })
        assertEquals(listOf(DataTaskOutputState.EXPIRED), cancelled.outputs.map { it.state })
        assertFalse(File(context.cacheDir, expected.outputs.single().privateRelativePath!!).exists())
        assertEquals(retained, store.loadTask(retained.taskId))
        assertEquals("bundle com.example.one", File(context.cacheDir, retained.outputs.single().privateRelativePath!!).readText())
    }

    private fun cancellationCoordinator(owners: DataTaskOwnerRegistry = DataTaskOwnerRegistry()) =
        DataTaskCancellationCoordinator(RoomDataTaskCancellationActions(
            db.dataTaskDao(), ArchiveKeyHolder(Dispatchers.Unconfined), RestoreSourceGrantHolder(),
            RestoreSourceStager(unused<RestoreSourceStagingDependencies>()), owners,
            DataQueueWakeSignal { ServiceStartResult.Requested }, cleanup,
        ))

    private suspend fun completeNext(runtime: RoomDataSyncCoordinatorRuntime) {
        val claim = runtime.claimNext("session", UUID.randomUUID().toString())!!
        val result = runtime.executeClaim(claim, runtime.checkpointSink(claim))
        assertEquals(DataTaskItemTerminalState.SUCCEEDED, (result as DataTaskRunOutcome.ItemCompleted).result.terminalState)
        assertEquals(DataTaskSinkWrite.APPLIED, runtime.persistOutcome(claim, result))
        runtime.cleanupClaim(claim)
    }

    private fun selection() = AppShareRequest(apps.apps.value.map { AppShareTarget(it.packageName, it.appName) })

    private fun runtime(): RoomDataSyncCoordinatorRuntime {
        val cipher = AppArchiveCipher()
        val openArchive = OpenArchiveUseCase(cipher, Dispatchers.Unconfined)
        val gateway = unused<AppDataArchiveGateway>()
        val probe = unused<AppDataProbe>()
        val files = unused<AppBundleFileStore>()
        val barrier = LaunchSweepBarrier().apply { markSwept() }
        return RoomDataSyncCoordinatorRuntime(
            context, db.dataTaskDao(), LegacyDataWorkDrainGate(flowOf(emptyList())), barrier,
            ArchiveKeyHolder(Dispatchers.Unconfined), RestoreSourceGrantHolder(),
            RestoreSourceStager(unused<RestoreSourceStagingDependencies>()),
            BackupAppArchiveUseCase(gateway, unused(), cipher, probe, packages, openArchive),
            RestoreAppArchiveUseCase(gateway, unused(), unused(), unused(), cipher, packages),
            ExportAppUseCase(builder, unused(), files, Dispatchers.Unconfined),
            apps, builder, files, unused(), probe, unused(), openArchive,
            ReadInstalledAppFactsUseCase(apps, gateway), JobRegistry(),
            DefaultSharePrepareTaskOperations(context, barrier, apps, builder, Dispatchers.Unconfined),
            packages, ReadyShareNotification(context),
            cleanup,
            Dispatchers.Unconfined,
        )
    }

    /** Any accidental archive/export dependency call fails this integration test immediately. */
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> throw AssertionError("Share execution called ${T::class.java.simpleName}.${method.name}") } as T
}
