// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.repository.AppBundleBuilderImpl
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.*
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SharePrepareProductionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val taskId = UUID.fromString("11111111-1111-1111-1111-111111111117")
    private val pkg = "com.example.app"
    private val stage get() = "share_ready/item-$taskId-0"
    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        private val cache = temporary.newFolder()
        private val external = temporary.newFolder()
        override fun getCacheDir() = cache
        override fun getExternalCacheDir() = external
    }
    private fun app() = AppInfo(packageName = pkg, appName = "Example", versionName = "1.0",
        publicSourceDir = temporary.newFile().apply { writeText("base bytes") }.path)
    private fun builder(context: Context) = AppBundleBuilderImpl(context, FakeSystemRepository(), ApksMetadataGenerator(), Dispatchers.Unconfined)
    private fun operations(context: Context, apps: FakeAppRepository, builder: AppBundleBuilder = builder(context), barrier: LaunchSweepBarrier = LaunchSweepBarrier().apply { markSwept() }) =
        DefaultSharePrepareTaskOperations(context, barrier, apps, builder, Dispatchers.Unconfined)
    private fun request(format: SharePrepareFormat = SharePrepareFormat.AUTO) = DataTaskExecutionRequest(
        taskId, DataTaskExecutionPayload.SharePrepare(format, DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY),
        DataTaskExecutionItem(0, pkg, "Example", "item-$taskId-0", 1), 1, null,
    )
    private val sink = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED }

    @Test fun `production APK publication uses owned path and replay reuses only completed file`() = runTest {
        val context = context()
        val app = app()
        val apps = FakeAppRepository(listOf(app))
        val ops = operations(context, apps)
        val first = SharePrepareTaskRunner(ops, Dispatchers.Unconfined).run(request(), sink) as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.SUCCEEDED, first.result.terminalState)
        val output = first.result.outputs.single()
        val file = File(context.cacheDir, output.privateRelativePath!!)
        assertEquals("$stage/$pkg/Example_1.0_com.example.app_0.apk", output.privateRelativePath)
        assertEquals("base bytes", file.readText())
        // A completed atomic publication survives a process dying before Room settlement.
        File(app.publicSourceDir!!).writeText("changed source not read on reconciliation")
        val replay = SharePrepareTaskRunner(operations(context, apps), Dispatchers.Unconfined).run(request(), sink) as DataTaskRunOutcome.ItemCompleted
        assertEquals(output.outputId, replay.result.outputs.single().outputId)
        assertEquals("base bytes", file.readText())
        assertFalse(File(context.cacheDir, "share_work/item-$taskId-0/$pkg").exists())
    }

    @Test fun `fresh metadata drives AUTO split contents and uninstalled rows are refused`() = runTest {
        val context = context()
        val app = app()
        val apps = FakeAppRepository(listOf(app))
        val ops = operations(context, apps)
        val split = temporary.newFile("config.apk").apply { writeText("split bytes") }
        apps.apps.value = listOf(app.copy(splitPublicSourceDirs = listOf(split.path)))
        val outcome = SharePrepareTaskRunner(ops, Dispatchers.Unconfined).run(request(), sink) as DataTaskRunOutcome.ItemCompleted
        val output = outcome.result.outputs.single()
        assertTrue(output.displayName.endsWith(".apks"))
        ZipFile(File(context.cacheDir, output.privateRelativePath!!)).use { zip ->
            assertTrue(zip.entries().asSequence().any { it.name == "config.apk" })
        }
        apps.apps.value = listOf(app.copy(isInstalled = false))
        assertNull(ops.loadApp(pkg))
    }

    @Test fun `the Android framework package is a valid single component package name`() = runTest {
        val context = context()
        val framework = app().copy(packageName = "android")
        val request = request().let { it.copy(item = it.item.copy(packageName = "android")) }
        val outcome = SharePrepareTaskRunner(operations(context, FakeAppRepository(listOf(framework))), Dispatchers.Unconfined)
            .run(request, sink) as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.SUCCEEDED, outcome.result.terminalState)
        assertTrue(File(context.cacheDir, outcome.result.outputs.single().privateRelativePath!!).isFile)
    }

    @Test fun `launch barrier prevents any staging until cleanup releases it`() = runTest {
        val context = context()
        val barrier = LaunchSweepBarrier()
        val ops = operations(context, FakeAppRepository(listOf(app())), barrier = barrier)
        val task = async { SharePrepareTaskRunner(ops, Dispatchers.Unconfined).run(request(), sink) }
        yield()
        assertFalse(File(context.cacheDir, "share_work").exists())
        assertFalse(task.isCompleted)
        barrier.markSwept()
        assertEquals(DataTaskItemTerminalState.SUCCEEDED, (task.await() as DataTaskRunOutcome.ItemCompleted).result.terminalState)
    }

    @Test fun `XAPK OBB staging cannot delete the same package legacy staging`() = runTest {
        val context = context()
        val legacy = File(context.externalCacheDir, "obb_out/$pkg/legacy.obb").apply {
            parentFile!!.mkdirs(); writeText("legacy bytes")
        }
        val system = FakeSystemRepository()
        val realBuilder = AppBundleBuilderImpl(context, system, ApksMetadataGenerator(), Dispatchers.Unconfined)
        val result = SharePrepareTaskRunner(operations(context, FakeAppRepository(listOf(app())), realBuilder), Dispatchers.Unconfined)
            .run(request(SharePrepareFormat.XAPK), sink) as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.SUCCEEDED, result.result.terminalState)
        assertTrue("XAPK cleanup must not touch legacy staging for the same package", legacy.isFile)
    }

    @Test fun `cancellation at atomic publication removes the uncommitted ready leaf`() = runTest {
        val context = context()
        val app = app()
        val ops = operations(context, FakeAppRepository(listOf(app)))
        val boundary = VerifiedOperationBoundary {
            if (File(context.cacheDir, "$stage/$pkg").listFiles()?.any { it.isFile } == true) {
                throw CancellationException("cancel after publication")
            }
        }
        try {
            ops.buildBundle(app, stage, BundleFormat.APK, "owned.apk", PrivilegeExecutionContext(),
                VerifiedProgress { _ -> }, boundary)
            fail("publication callback must cancel the build")
        } catch (_: CancellationException) { }
        assertFalse(File(context.cacheDir, "$stage/$pkg/owned.apk").exists())
        assertFalse(File(context.cacheDir, "share_work/item-$taskId-0/$pkg").exists())
    }

    @Test fun `execution rejects another tasks safe looking staging identity before IO`() = runTest {
        val context = context()
        val ops = operations(context, FakeAppRepository(listOf(app())))
        val request = request().let { it.copy(item = it.item.copy(deterministicStagingIdentity = "item-${UUID.randomUUID()}-0")) }
        val outcome = SharePrepareTaskRunner(ops, Dispatchers.Unconfined).run(request, sink)
        assertTrue(outcome is DataTaskRunOutcome.TaskFailed)
        assertFalse(File(context.cacheDir, "share_work").exists())
        assertFalse(File(context.cacheDir, "share_ready").exists())
    }

    @Test fun `cancelled builder cleans owned work without deleting another task or legacy output`() = runTest {
        val context = context()
        val legacy = File(context.cacheDir, "share_temp/$pkg/legacy.apk").apply { parentFile!!.mkdirs(); writeText("keep") }
        val other = File(context.cacheDir, "share_ready/item-${UUID.randomUUID()}-0/$pkg/other.apk").apply { parentFile!!.mkdirs(); writeText("keep") }
        val entered = CompletableDeferred<Unit>()
        val blocking = object : AppBundleBuilder {
            override suspend fun build(appInfo: AppInfo, cacheSubDir: String, format: BundleFormat, fileName: String?, execution: PrivilegeExecutionContext): Result<File> {
                File(context.cacheDir, "$cacheSubDir/$pkg/partial").apply { parentFile!!.mkdirs(); writeText("partial") }
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val running = launch { SharePrepareTaskRunner(operations(context, FakeAppRepository(listOf(app())), blocking), Dispatchers.Unconfined).run(request(), sink) }
        entered.await()
        running.cancelAndJoin()
        assertFalse(File(context.cacheDir, "share_work/item-$taskId-0/$pkg").exists())
        assertTrue(legacy.isFile)
        assertTrue(other.isFile)
        assertFalse(File(context.cacheDir, "$stage/$pkg").exists())
    }
}
