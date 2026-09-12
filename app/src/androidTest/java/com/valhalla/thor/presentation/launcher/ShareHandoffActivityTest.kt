// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.data.repository.AppBundleFileStoreImpl
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.data.source.local.room.NewDataTaskItem
import com.valhalla.thor.data.source.local.room.NewDataTaskRow
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.NewDataTaskOutput
import com.valhalla.thor.domain.model.SharePrepareFormat
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.presentation.share.ReadyShareAccessLock
import com.valhalla.thor.presentation.share.ReadyShareRetentionDependencies
import com.valhalla.thor.presentation.share.ReadyShareRetentionSweeper
import com.valhalla.thor.presentation.share.ShareIntentFactory
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ShareHandoffActivityTest {
    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    internal val context: Context
        get() = instrumentation.targetContext

    private lateinit var database: AppDatabase
    private lateinit var dao: DataTaskDao
    private lateinit var fileStore: AppBundleFileStoreImpl
    private lateinit var productionFactory: ShareIntentFactory
    private lateinit var accessLock: ReadyShareAccessLock

    @Before
    fun setUp() {
        val koin = requireNotNull(GlobalContext.getOrNull())
        productionFactory = requireNotNull(
            GlobalContext.get().getOrNull<ShareIntentFactory>(),
        )
        // Resolve the running application's singleton, just as for productionFactory above.
        // This test compilation has no statically loaded application module of its own.
        accessLock = requireNotNull(koin.getOrNull<ReadyShareAccessLock>())
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.dataTaskDao()
        fileStore = AppBundleFileStoreImpl(context, Dispatchers.IO)
        koin.declare(
            ShareIntentFactory(context, dao, fileStore),
            allowOverride = true,
        )
        shareRoot().deleteRecursively()
    }

    @After
    fun tearDown() {
        if (::productionFactory.isInitialized) {
            GlobalContext.getOrNull()?.declare(
                productionFactory,
                allowOverride = true,
            )
        }
        if (::database.isInitialized) database.close()
        shareRoot().deleteRecursively()
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(OUTSIDE_PREFIX) }
            ?.forEach(File::deleteRecursively)
        File(context.cacheDir, TRAVERSAL_FILE).delete()
    }

    @Test
    fun oneOutputLaunchesSendChooserWithMirroredReadOnlyStream() = runBlocking {
        val taskId = UUID.randomUUID()
        val output = output(taskId, ordinal = 0, displayName = "alpha.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)

        val target = launchAndCaptureTarget(taskId)
        val stream = requireNotNull(target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals(BundleFormat.APK.mime, target.type)
        assertEquals(expectedUri(output), stream)
        assertEquals(stream, requireNotNull(target.clipData).getItemAt(0).uri)
        assertReadOnlyGrant(target)
        assertEquals("content", stream.scheme)
        assertEquals("${BuildConfig.APPLICATION_ID}.provider", stream.authority)
    }

    @Test
    fun multipleOutputsLaunchSendMultipleWithExactDaoOrderMirroredInClipData() = runBlocking {
        val taskId = UUID.randomUUID()
        val outputs = listOf(
            output(taskId, ordinal = 0, displayName = "first.apk"),
            output(taskId, ordinal = 1, displayName = "second.apk"),
        )
        persistReadyTask(taskId, outputs)
        outputs.forEach(::writeOutput)

        val target = launchAndCaptureTarget(taskId)
        val streams = requireNotNull(
            target.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        )
        val clipStreams = requireNotNull(target.clipData).let { clip ->
            (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        }

        assertEquals(Intent.ACTION_SEND_MULTIPLE, target.action)
        assertEquals(outputs.map(::expectedUri), streams)
        assertEquals(streams, clipStreams)
        assertReadOnlyGrant(target)
    }

    @Test
    fun readyPartialTaskLaunchesItsReadyOutput() = runBlocking {
        val taskId = UUID.randomUUID()
        val output = output(taskId, ordinal = 0, displayName = "partial.apk")
        persistReadyTask(
            taskId = taskId,
            outputs = listOf(output),
            failedItemCount = 1,
        )
        writeOutput(output)

        val target = launchAndCaptureTarget(taskId)

        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals(expectedUri(output), target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertReadOnlyGrant(target)
    }

    @Test
    fun expiredOutputFinishesBeforeLaunchingChooser() = runBlocking {
        val taskId = UUID.randomUUID()
        val output = output(
            taskId = taskId,
            ordinal = 0,
            displayName = "expired.apk",
            expiresAtEpochMs = EXPIRED_AT_MS,
        )
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)

        assertNoChooser(taskId)
    }

    @Test
    fun traversingPersistedPathFinishesBeforeLaunchingChooser() = runBlocking {
        val taskId = UUID.randomUUID()
        val output = output(taskId, ordinal = 0, displayName = "escape.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)
        File(context.cacheDir, TRAVERSAL_FILE).writeBytes(output.bytes)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE data_task_outputs SET private_relative_path = ? WHERE task_id = ?",
            arrayOf("share_ready/../$TRAVERSAL_FILE", taskId.toString()),
        )

        assertNoChooser(taskId)
    }

    @Test
    fun symbolicLinkOutputFinishesBeforeLaunchingChooser() = runBlocking {
        val taskId = UUID.randomUUID()
        val output = output(taskId, ordinal = 0, displayName = "linked.apk")
        persistReadyTask(taskId, listOf(output))
        val outside = File(context.cacheDir, "$OUTSIDE_PREFIX$taskId").apply {
            writeBytes(output.bytes)
        }
        File(context.cacheDir, output.relativePath).also { link ->
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }

        assertNoChooser(taskId)
    }

    @Test
    fun deterministicIdentityMismatchFinishesBeforeLaunchingChooser() = runBlocking {
        val taskId = UUID.randomUUID()
        val output = output(taskId, ordinal = 0, displayName = "wrong-identity.apk")
        persistReadyTask(
            taskId = taskId,
            outputs = listOf(output),
            firstItemIdentity = "item-$taskId-9",
        )
        writeOutput(output)

        assertNoChooser(taskId)
    }

    @Test
    fun missingOutputFinishesBeforeLaunchingChooser() = runBlocking {
        val taskId = UUID.randomUUID()
        val output = output(taskId, ordinal = 0, displayName = "missing.apk")
        persistReadyTask(taskId, listOf(output))

        assertNoChooser(taskId)
    }

    @Test
    fun oneMissingOutputRefusesTheWholeOtherwiseReadySet() = runBlocking {
        val taskId = UUID.randomUUID()
        val outputs = listOf(
            output(taskId, ordinal = 0, displayName = "present.apk"),
            output(taskId, ordinal = 1, displayName = "missing.apk"),
        )
        persistReadyTask(taskId, outputs)
        writeOutput(outputs.first())

        assertNoChooser(taskId)

        assertTrue(File(context.cacheDir, outputs.first().relativePath).isFile)
    }

    @Test
    fun oneExpiredOutputRefusesTheWholeOtherwiseReadySet() = runBlocking {
        val taskId = UUID.randomUUID()
        val outputs = listOf(
            output(taskId, ordinal = 0, displayName = "expired.apk", expiresAtEpochMs = EXPIRED_AT_MS),
            output(taskId, ordinal = 1, displayName = "valid.apk"),
        )
        persistReadyTask(taskId, outputs)
        outputs.forEach(::writeOutput)

        assertNoChooser(taskId)

        assertTrue(File(context.cacheDir, outputs.last().relativePath).isFile)
    }

    @Test
    fun firstExpiredLeafDisablesHandoffWhileLaterLeafRemainsPrivate() = runBlocking {
        val taskId = UUID.randomUUID()
        val outputs = listOf(
            output(taskId, ordinal = 0, displayName = "due.apk", expiresAtEpochMs = EXPIRED_AT_MS),
            output(taskId, ordinal = 1, displayName = "later.apk"),
        )
        persistReadyTask(taskId, outputs)
        outputs.forEach(::writeOutput)
        val lock = accessLock

        retentionSweeper(lock, EXPIRED_AT_MS).sweep()

        assertFalse(File(context.cacheDir, outputs.first().relativePath).exists())
        assertTrue(File(context.cacheDir, outputs.last().relativePath).isFile)
        assertEquals(DataTaskState.EXPIRED, dao.loadTask(taskId.toString())!!.state)
        assertNoChooser(taskId)
    }

    @Test
    fun handoffCannotFinishOrLaunchChooserDuringRetentionDeletion() = runBlocking {
        val taskId = UUID.randomUUID()
        // Handoff's real clock still sees both outputs as valid; the injected retention clock is due.
        val deadline = System.currentTimeMillis() + VALID_FOR_MS
        val outputs = listOf(
            output(taskId, ordinal = 0, displayName = "first.apk", expiresAtEpochMs = deadline),
            output(taskId, ordinal = 1, displayName = "second.apk", expiresAtEpochMs = deadline),
        )
        persistReadyTask(taskId, outputs)
        outputs.forEach(::writeOutput)
        val lock = accessLock
        val deletionEntered = CountDownLatch(1)
        val releaseDeletion = CountDownLatch(1)
        val sweeper = retentionSweeper(lock, deadline) { file ->
            if (file.name == outputs.first().displayName) {
                deletionEntered.countDown()
                check(releaseDeletion.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "deletion release timed out" }
            }
        }
        val sweep = async(Dispatchers.IO) { sweeper.sweep() }
        try {
            assertTrue("retention did not delete the first leaf", deletionEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertHandoffWaitsThenRefuses(taskId) { releaseDeletion.countDown() }
            sweep.await()
            assertEquals(DataTaskState.EXPIRED, dao.loadTask(taskId.toString())!!.state)
        } finally {
            releaseDeletion.countDown()
            sweep.cancel()
        }
    }

    @Test
    fun handoffReloadsReplacementAfterWaitingForSharedLock() = runBlocking {
        val taskId = UUID.randomUUID()
        val original = output(taskId, ordinal = 0, displayName = "original.apk")
        val replacement = output(taskId, ordinal = 0, displayName = "replacement.apk")
        persistReadyTask(taskId, listOf(original))
        writeOutput(original)
        val lock = accessLock
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutation = async(Dispatchers.IO) {
            lock.withLock {
                locked.countDown()
                check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "mutation release timed out" }
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE data_task_outputs SET output_id = ?, private_relative_path = ?, display_name = ? WHERE task_id = ?",
                    arrayOf(UUID.randomUUID().toString(), replacement.relativePath, replacement.displayName, taskId.toString()),
                )
            }
        }
        try {
            assertTrue(locked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertHandoffWaitsThenRefuses(taskId) { release.countDown() }
            mutation.await()
            assertTrue("the replaced row must not reuse the stale existing file", File(context.cacheDir, original.relativePath).isFile)
        } finally {
            release.countDown()
            mutation.cancel()
        }
    }

    private fun retentionSweeper(
        lock: ReadyShareAccessLock,
        nowMs: Long,
        afterDelete: (File) -> Unit = {},
    ): ReadyShareRetentionSweeper {
        val store = DataTaskStore(dao)
        return ReadyShareRetentionSweeper(object : ReadyShareRetentionDependencies {
            override val cacheDirectory: File = context.cacheDir
            override val accessLock = lock
            override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
            override fun nowMs(): Long = nowMs
            override fun deleteFile(file: File): Boolean = file.delete().also { afterDelete(file) }
            override suspend fun expiredReadyShareTasks(nowMs: Long): List<DataTaskSnapshot> =
                store.expiredReadyShareTasks(nowMs)
            override suspend fun readyShareRetentionSnapshot(taskId: UUID, nowMs: Long): DataTaskSnapshot? =
                store.readyShareRetentionSnapshot(taskId, nowMs)
            override suspend fun markReadyTaskExpiredAfterCleanup(
                expected: DataTaskSnapshot,
                outputIds: List<UUID>,
                nowMs: Long,
            ): Boolean = store.markReadyTaskExpiredAfterCleanup(expected, outputIds, nowMs)
        })
    }

    private fun assertHandoffWaitsThenRefuses(taskId: UUID, releaseMutation: () -> Unit) {
        val chooserMonitor = ChooserMonitor()
        val activityMonitor = instrumentation.addMonitor(ShareHandoffActivity::class.java.name, null, false)
        instrumentation.addMonitor(chooserMonitor)
        try {
            context.startActivity(ShareHandoffActivity.intent(context, taskId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(activityMonitor, TIMEOUT_MS)) {
                "ShareHandoffActivity did not launch"
            }
            instrumentation.waitForIdleSync()
            assertEquals(null, chooserMonitor.await(NO_CHOOSER_GRACE_MS))
            assertFalse("handoff must wait for deletion/mutation rather than inspect a partial set", activity.isFinishing || activity.isDestroyed)

            releaseMutation()
            awaitFinished(activity)
            assertEquals(0, chooserMonitor.hits)
            assertEquals(null, chooserMonitor.await(NO_CHOOSER_GRACE_MS))
        } finally {
            releaseMutation()
            instrumentation.removeMonitor(chooserMonitor)
            instrumentation.removeMonitor(activityMonitor)
        }
    }

    private fun launchAndCaptureTarget(taskId: UUID): Intent {
        val chooserMonitor = ChooserMonitor()
        val activityMonitor = instrumentation.addMonitor(
            ShareHandoffActivity::class.java.name,
            null,
            false,
        )
        instrumentation.addMonitor(chooserMonitor)
        try {
            context.startActivity(
                ShareHandoffActivity.intent(context, taskId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val activity = requireNotNull(
                instrumentation.waitForMonitorWithTimeout(activityMonitor, TIMEOUT_MS)
            ) { "ShareHandoffActivity did not launch" }
            val chooser = requireNotNull(chooserMonitor.await(TIMEOUT_MS)) {
                "ShareHandoffActivity did not launch a chooser"
            }
            awaitFinished(activity)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            return requireNotNull(
                chooser.getParcelableExtra(Intent.EXTRA_INTENT)
            ) { "chooser did not contain a target share intent" }
        } finally {
            instrumentation.removeMonitor(chooserMonitor)
            instrumentation.removeMonitor(activityMonitor)
        }
    }

    private fun assertNoChooser(taskId: UUID) {
        val chooserMonitor = ChooserMonitor()
        val activityMonitor = instrumentation.addMonitor(
            ShareHandoffActivity::class.java.name,
            null,
            false,
        )
        instrumentation.addMonitor(chooserMonitor)
        try {
            context.startActivity(
                ShareHandoffActivity.intent(context, taskId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val activity = requireNotNull(
                instrumentation.waitForMonitorWithTimeout(activityMonitor, TIMEOUT_MS)
            ) { "ShareHandoffActivity did not launch" }
            awaitFinished(activity)

            assertEquals(0, chooserMonitor.hits)
            assertEquals(null, chooserMonitor.await(NO_CHOOSER_GRACE_MS))
        } finally {
            instrumentation.removeMonitor(chooserMonitor)
            instrumentation.removeMonitor(activityMonitor)
        }
    }

    private fun awaitFinished(activity: Activity) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!activity.isFinishing && !activity.isDestroyed &&
            SystemClock.uptimeMillis() < deadline
        ) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(
            "ShareHandoffActivity did not finish",
            activity.isFinishing || activity.isDestroyed,
        )
    }

    private fun assertReadOnlyGrant(intent: Intent) {
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    private suspend fun persistReadyTask(
        taskId: UUID,
        outputs: List<OutputSpec>,
        firstItemIdentity: String = "item-$taskId-0",
        failedItemCount: Int = 0,
    ) {
        val ordered = outputs.sortedBy(OutputSpec::ordinal)
        val items = ordered.map { spec ->
            NewDataTaskItem(
                ordinal = spec.ordinal,
                packageName = spec.packageName,
                displayLabel = spec.packageName,
                deterministicStagingIdentity = if (spec.ordinal == 0) {
                    firstItemIdentity
                } else {
                    "item-$taskId-${spec.ordinal}"
                },
            )
        } + List(failedItemCount) { index ->
            val ordinal = ordered.size + index
            NewDataTaskItem(
                ordinal = ordinal,
                packageName = "com.example.failed$index",
                displayLabel = "failed-$index",
                deterministicStagingIdentity = "item-$taskId-$ordinal",
            )
        }
        dao.insertTask(
            NewDataTaskRow(
                taskId = taskId.toString(),
                payloadSchemaVersion = 1,
                kind = DataTaskKind.SHARE_PREPARE,
                targetKey = "share:$taskId",
                initialState = DataTaskState.QUEUED,
                detail = StoredDataTaskDetail.SharePrepare(
                    requestedFormat = SharePrepareFormat.APK,
                    publicationPolicy = DataTaskPublicationPolicy
                        .PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
                    deterministicStagingIdentity = "stage-$taskId",
                ),
                items = items,
                createdAtEpochMs = CREATED_AT_MS,
            )
        )
        val taskClaim = "task-claim-$taskId"
        val first = requireNotNull(
            dao.claimOldestRunnableWork(
                sessionToken = "session-$taskId",
                taskClaimToken = taskClaim,
                itemClaimToken = "item-claim-$taskId-0",
                nowMs = CLAIMED_AT_MS,
                leaseUntilMs = System.currentTimeMillis() + LEASE_DURATION_MS,
            )
        )
        items.indices.forEach { index ->
            val claimed = if (index == 0) {
                first.item
            } else {
                requireNotNull(
                    dao.claimNextPendingItem(
                        taskId = taskId.toString(),
                        taskClaimToken = taskClaim,
                        itemClaimToken = "item-claim-$taskId-$index",
                        nowMs = CLAIMED_AT_MS + index,
                        leaseUntilMs = System.currentTimeMillis() + LEASE_DURATION_MS,
                    )
                )
            }
            val output = ordered.getOrNull(index)
            assertEquals(index, claimed.ordinal)
            assertTrue(
                dao.completeClaimedItem(
                    taskId = taskId.toString(),
                    ordinal = index,
                    taskClaimToken = taskClaim,
                    itemClaimToken = claimed.claimToken,
                    result = DataTaskItemResult(
                        terminalState = if (output == null) {
                            DataTaskItemTerminalState.FAILED
                        } else {
                            DataTaskItemTerminalState.SUCCEEDED
                        },
                        resultCode = DataTaskResultCode(
                            if (output == null) "TEST_FAILED" else "TEST_COMPLETE"
                        ),
                        warnings = emptyList(),
                        outputs = if (output == null) {
                            emptyList()
                        } else {
                            listOf(
                                NewDataTaskOutput(
                                    outputId = UUID.nameUUIDFromBytes(
                                        "output:$taskId:${output.ordinal}".toByteArray()
                                    ),
                                    privateRelativePath = output.relativePath,
                                    displayName = output.displayName,
                                    mimeType = BundleFormat.APK.mime,
                                    byteSize = output.bytes.size.toLong(),
                                    state = DataTaskOutputState.READY,
                                    expiresAtEpochMs = output.expiresAtEpochMs,
                                )
                            )
                        },
                        finishedAtEpochMs = FINISHED_AT_MS + index,
                    ),
                )
            )
        }
        assertTrue(
            dao.finishClaimedTaskIfDrained(
                taskId = taskId.toString(),
                claimToken = taskClaim,
                nowMs = FINALIZED_AT_MS,
            )
        )
    }

    private fun output(
        taskId: UUID,
        ordinal: Int,
        displayName: String,
        expiresAtEpochMs: Long = System.currentTimeMillis() + VALID_FOR_MS,
    ): OutputSpec {
        val packageName = "com.example.item$ordinal"
        return OutputSpec(
            ordinal = ordinal,
            packageName = packageName,
            displayName = displayName,
            expiresAtEpochMs = expiresAtEpochMs,
            bytes = "payload-$ordinal".toByteArray(),
            relativePath = "share_ready/item-$taskId-$ordinal/$packageName/$displayName",
        )
    }

    private fun writeOutput(output: OutputSpec) {
        File(context.cacheDir, output.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(output.bytes)
        }
    }

    private fun expectedUri(output: OutputSpec): Uri =
        fileStore.shareUri(File(context.cacheDir, output.relativePath).canonicalFile).toUri()

    private fun shareRoot(): File = File(context.cacheDir, "share_ready")

    private class ChooserMonitor : Instrumentation.ActivityMonitor() {
        private val started = CountDownLatch(1)

        @Volatile
        private var chooser: Intent? = null

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CHOOSER) return null
            chooser = Intent(intent)
            started.countDown()
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }

        fun await(timeoutMs: Long): Intent? =
            if (started.await(timeoutMs, TimeUnit.MILLISECONDS)) chooser else null
    }

    private class OutputSpec(
        val ordinal: Int,
        val packageName: String,
        val displayName: String,
        val expiresAtEpochMs: Long,
        val bytes: ByteArray,
        val relativePath: String,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val NO_CHOOSER_GRACE_MS = 100L
        const val POLL_INTERVAL_MS = 10L
        const val VALID_FOR_MS = 60_000L
        const val LEASE_DURATION_MS = 60_000L
        const val CREATED_AT_MS = 1_000L
        const val CLAIMED_AT_MS = 1_100L
        const val FINISHED_AT_MS = 1_500L
        const val FINALIZED_AT_MS = 2_000L
        const val EXPIRED_AT_MS = 3_000L
        const val OUTSIDE_PREFIX = "share-handoff-outside-"
        const val TRAVERSAL_FILE = "share-handoff-traversal.apk"
    }
}
