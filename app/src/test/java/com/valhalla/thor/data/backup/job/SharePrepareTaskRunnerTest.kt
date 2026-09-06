// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.SharePrepareFormat
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePrepareTaskRunnerTest {
    private val temporaryFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        temporaryFiles.forEach { it.deleteRecursively() }
    }

    @Test
    fun `prepared output is ready with only private metadata and a 24 hour expiry`() = runBlocking {
        val operations = RecordingShareOperations(
            bundle = bundle(
                "Example_1.0_com.example.app_0.apk",
                "payload"
            )
        )
        val finishedAt = 5_000L

        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined) { finishedAt }
            .run(shareRequest(), acceptingCheckpoints()) as DataTaskRunOutcome.ItemCompleted

        assertEquals(DataTaskItemTerminalState.SUCCEEDED, outcome.result.terminalState)
        val output = outcome.result.outputs.single()
        assertEquals(DataTaskOutputState.READY, output.state)
        assertEquals("Example_1.0_com.example.app_0.apk", output.displayName)
        assertEquals(BundleFormat.APK.mime, output.mimeType)
        assertEquals("payload".length.toLong(), output.byteSize)
        assertEquals(finishedAt + SHARE_READY_RETENTION_MS, output.expiresAtEpochMs)
        assertEquals(
            "share_ready/item-$TASK_ID-0/$PACKAGE_NAME/Example_1.0_com.example.app_0.apk",
            output.privateRelativePath,
        )
        assertFalse(requireNotNull(output.privateRelativePath).contains("://"))
    }

    @Test
    fun `replay discards incomplete item state and reuses deterministic output identity`() =
        runBlocking {
            val operations = RecordingShareOperations(
                bundle = bundle("Example_1.0_com.example.app_0.apk", "complete"),
                incompletePartPresent = true,
            )
            val runner = SharePrepareTaskRunner(operations, Dispatchers.Unconfined) { 7L }
            val request = shareRequest()

            val first =
                runner.run(request, acceptingCheckpoints()) as DataTaskRunOutcome.ItemCompleted
            operations.incompletePartPresent = true
            val second =
                runner.run(request, acceptingCheckpoints()) as DataTaskRunOutcome.ItemCompleted

            assertEquals(
                first.result.outputs.single().outputId,
                second.result.outputs.single().outputId
            )
            assertEquals(
                first.result.outputs.single().privateRelativePath,
                second.result.outputs.single().privateRelativePath,
            )
            assertEquals(2, operations.discards.size)
            assertEquals(2, operations.builds.size)
            assertTrue(operations.builds.all { it.stagingSubDir == "share_ready/item-$TASK_ID-0" })
            assertTrue(operations.builtOnlyAfterDiscard)
        }

    @Test
    fun `one share packaging failure completes only that item as failed`() = runBlocking {
        val operations = RecordingShareOperations(
            bundleResult = Result.failure(IllegalStateException("x".repeat(20_000))),
        )

        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined) { 8L }
            .run(shareRequest(), acceptingCheckpoints()) as DataTaskRunOutcome.ItemCompleted

        assertEquals(DataTaskItemTerminalState.FAILED, outcome.result.terminalState)
        assertEquals("SHARE_PREPARE_FAILED", outcome.result.resultCode.value)
        assertTrue(outcome.result.outputs.isEmpty())
        val reason = outcome.result.warnings.single().arguments.single()
        assertTrue(reason.length <= MAX_JOB_MESSAGE_CHARS)
    }

    @Test
    fun `builder exception paths never enter durable warning arguments`() = runBlocking {
        val operations = RecordingShareOperations(bundleResult = Result.failure(
            java.io.IOException("/data/user/0/private/cache/share_work/private.apk: access denied"),
        ))
        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined)
            .run(shareRequest(), acceptingCheckpoints()) as DataTaskRunOutcome.ItemCompleted
        assertEquals("the operation could not be completed", outcome.result.warnings.single().arguments.single())
    }

    @Test
    fun `a successful build without a complete file is not exposed as ready`() = runBlocking {
        val missing = File(
            Files.createTempDirectory("missing_share_runner_").toFile().also(temporaryFiles::add),
            "Example_1.0_com.example.app_0.apk",
        )
        val operations = RecordingShareOperations(bundle = missing)

        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined) { 8L }
            .run(shareRequest(), acceptingCheckpoints()) as DataTaskRunOutcome.ItemCompleted

        assertEquals(DataTaskItemTerminalState.FAILED, outcome.result.terminalState)
        assertEquals("SHARE_PREPARE_STAGING_FAILED", outcome.result.resultCode.value)
        assertTrue(outcome.result.outputs.isEmpty())
    }

    @Test
    fun `share cancellation returned as a failed result is rethrown unchanged`() {
        val cancellation = CancellationException("stop share")
        val operations = RecordingShareOperations(bundleResult = Result.failure(cancellation))

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                SharePrepareTaskRunner(operations, Dispatchers.Unconfined)
                    .run(shareRequest(), acceptingCheckpoints())
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `traversing staging identity is rejected before any operation`() = runBlocking {
        val operations = RecordingShareOperations(bundle = bundle("unused.apk", "payload"))

        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
            shareRequest(stagingIdentity = ".."),
            acceptingCheckpoints(),
        ) as DataTaskRunOutcome.TaskFailed

        assertEquals("SHARE_PREPARE_REQUEST_MISMATCH", outcome.resultCode.value)
        assertTrue(operations.discards.isEmpty())
        assertTrue(operations.builds.isEmpty())
    }

    @Test
    fun `verified share bytes persist throttled capture checkpoints`() = runBlocking {
        val operations = RecordingShareOperations(
            bundle = bundle("Example_1.0_com.example.app_0.apk", "payload"),
            progressScript = { progress, _ ->
                progress.onBytesWritten(0L)
                progress.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES / 2)
                progress.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES / 2)
            },
        )
        val stages = mutableListOf<com.valhalla.thor.domain.model.DataTaskStage>()

        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
            shareRequest(),
            DataTaskCheckpointSink {
                stages += it.stage
                DataTaskSinkWrite.APPLIED
            },
        )

        assertTrue(outcome is DataTaskRunOutcome.ItemCompleted)
        assertEquals(
            listOf(
                com.valhalla.thor.domain.model.DataTaskStage.PREPARING,
                com.valhalla.thor.domain.model.DataTaskStage.CAPTURING,
                com.valhalla.thor.domain.model.DataTaskStage.CAPTURING,
            ),
            stages,
        )
    }

    @Test
    fun `verified share operation boundary checkpoints immediately and resets byte throttle`() =
        runBlocking {
            val operations = RecordingShareOperations(
                bundle = bundle("Example_1.0_com.example.app_0.apk", "payload"),
                progressScript = { progress, boundary ->
                    progress.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES - 1L)
                    boundary.onOperationCompleted()
                    progress.onBytesWritten(1L)
                },
            )
            val stages = mutableListOf<com.valhalla.thor.domain.model.DataTaskStage>()

            val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
                shareRequest(),
                DataTaskCheckpointSink {
                    stages += it.stage
                    DataTaskSinkWrite.APPLIED
                },
            )

            assertTrue(outcome is DataTaskRunOutcome.ItemCompleted)
            assertEquals(
                listOf(
                    com.valhalla.thor.domain.model.DataTaskStage.PREPARING,
                    com.valhalla.thor.domain.model.DataTaskStage.CAPTURING,
                    com.valhalla.thor.domain.model.DataTaskStage.CAPTURING,
                ),
                stages,
            )
        }

    @Test
    fun `ownership loss from share operation boundary aborts before later side effects`() =
        runBlocking {
            val operations = RecordingShareOperations(
                bundle = bundle("Example_1.0_com.example.app_0.apk", "payload"),
                progressScript = { _, boundary -> boundary.onOperationCompleted() },
            )
            var checkpointCount = 0

            val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
                shareRequest(),
                DataTaskCheckpointSink {
                    checkpointCount++
                    if (checkpointCount < 3) DataTaskSinkWrite.APPLIED
                    else DataTaskSinkWrite.OWNERSHIP_LOST
                },
            )

            assertSame(DataTaskRunOutcome.OwnershipLost, outcome)
            assertFalse(operations.sideEffectAfterProgress)
        }

    @Test
    fun `ownership loss from verified share progress aborts before later side effects`() =
        runBlocking {
            val operations = RecordingShareOperations(
                bundle = bundle("Example_1.0_com.example.app_0.apk", "payload"),
                progressScript = { progress, _ ->
                    progress.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES)
                },
            )
            var checkpointCount = 0

            val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
                shareRequest(),
                DataTaskCheckpointSink {
                    checkpointCount++
                    if (checkpointCount < 3) DataTaskSinkWrite.APPLIED
                    else DataTaskSinkWrite.OWNERSHIP_LOST
                },
            )

            assertSame(DataTaskRunOutcome.OwnershipLost, outcome)
            assertFalse(operations.sideEffectAfterProgress)
        }

    @Test
    fun `ownership loss prevents cleanup and bundle preparation`() = runBlocking {
        val operations = RecordingShareOperations(bundle = bundle("unused.apk", "payload"))

        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
            shareRequest(),
            DataTaskCheckpointSink { DataTaskSinkWrite.OWNERSHIP_LOST },
        )

        assertSame(DataTaskRunOutcome.OwnershipLost, outcome)
        assertTrue(operations.discards.isEmpty())
        assertTrue(operations.builds.isEmpty())
    }

    @Test
    fun `ownership loss before staging prevents cleanup and bundle preparation`() = runBlocking {
        val operations = RecordingShareOperations(bundle = bundle("unused.apk", "payload"))
        var checkpointCount = 0

        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
            shareRequest(),
            DataTaskCheckpointSink {
                checkpointCount++
                if (checkpointCount == 1) {
                    DataTaskSinkWrite.APPLIED
                } else {
                    DataTaskSinkWrite.OWNERSHIP_LOST
                }
            },
        )

        assertSame(DataTaskRunOutcome.OwnershipLost, outcome)
        assertTrue(operations.discards.isEmpty())
        assertTrue(operations.builds.isEmpty())
    }

    @Test
    fun `share preparation cancellation is rethrown and never becomes a failed item`() {
        val cancellation = CancellationException("stop share")
        val operations = RecordingShareOperations(buildFailure = cancellation)

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                SharePrepareTaskRunner(operations, Dispatchers.Unconfined)
                    .run(shareRequest(), acceptingCheckpoints())
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `AUTO prepares each app with its own APK or APKS format and matching metadata`() = runBlocking {
        for ((splitPaths, expectedFormat) in listOf(
            emptyList<String>() to BundleFormat.APK,
            listOf("/apps/$PACKAGE_NAME/split_config.apk") to BundleFormat.APKS,
        )) {
            val fileName = "Example_1.0_com.example.app_0.${expectedFormat.extension}"
            val operations = RecordingShareOperations(
                bundle = bundle(fileName, "complete"),
                splitPaths = splitPaths,
            )
            val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
                shareRequest(requestedFormat = SharePrepareFormat.AUTO),
                acceptingCheckpoints(),
            ) as DataTaskRunOutcome.ItemCompleted

            assertEquals(DataTaskItemTerminalState.SUCCEEDED, outcome.result.terminalState)
            assertEquals(expectedFormat, operations.builds.single().format)
            assertEquals(fileName, outcome.result.outputs.single().displayName)
            assertEquals(expectedFormat.mime, outcome.result.outputs.single().mimeType)
        }
    }

    @Test
    fun `explicit APK APKS and XAPK are never replaced by split auto detection`() = runBlocking {
        for ((requestedFormat, expectedFormat) in listOf(
            SharePrepareFormat.APK to BundleFormat.APK,
            SharePrepareFormat.APKS to BundleFormat.APKS,
            SharePrepareFormat.XAPK to BundleFormat.XAPK,
        )) {
            val fileName = "Example_1.0_com.example.app_0.${expectedFormat.extension}"
            val operations = RecordingShareOperations(
                bundle = bundle(fileName, "complete"),
                splitPaths = if (requestedFormat == SharePrepareFormat.APK) emptyList()
                    else listOf("/apps/$PACKAGE_NAME/split_config.apk"),
            )
            val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
                shareRequest(requestedFormat = requestedFormat),
                acceptingCheckpoints(),
            ) as DataTaskRunOutcome.ItemCompleted

            assertEquals(DataTaskItemTerminalState.SUCCEEDED, outcome.result.terminalState)
            assertEquals(expectedFormat, operations.builds.single().format)
            assertEquals(expectedFormat.mime, outcome.result.outputs.single().mimeType)
        }
    }

    @Test
    fun `share preparation preserves ARCHIVE lane and task identity for package reads`() = runBlocking {
        val operations = RecordingShareOperations(
            bundle = bundle("Example_1.0_com.example.app_0.apk", "payload"),
        )

        SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
            shareRequest(),
            acceptingCheckpoints(),
        )

        val execution = operations.builds.single().execution
        assertEquals(PrivilegeExecutionLane.ARCHIVE, execution.lane)
        assertEquals("archive.share.prepare", execution.commandClass.value)
        assertEquals(PACKAGE_NAME, execution.packageName)
        assertEquals(TASK_ID, execution.workRequestId)
    }

    @Test
    fun `explicit APK rejects a split app before staging or building`() = runBlocking {
        val operations = RecordingShareOperations(
            splitPaths = listOf("/apps/$PACKAGE_NAME/split_config.apk"),
        )
        val outcome = SharePrepareTaskRunner(operations, Dispatchers.Unconfined).run(
            shareRequest(requestedFormat = SharePrepareFormat.APK), acceptingCheckpoints(),
        ) as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.FAILED, outcome.result.terminalState)
        assertEquals("SHARE_PREPARE_SPLIT_APK_UNSUPPORTED", outcome.result.resultCode.value)
        assertTrue(operations.discards.isEmpty())
        assertTrue(operations.builds.isEmpty())
    }

    private fun shareRequest(
        stagingIdentity: String = "item-$TASK_ID-0",
        requestedFormat: SharePrepareFormat = SharePrepareFormat.APK,
    ) = DataTaskExecutionRequest(
        taskId = TASK_ID,
        payload = DataTaskExecutionPayload.SharePrepare(
            requestedFormat = requestedFormat,
            publicationPolicy = DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
        ),
        item = DataTaskExecutionItem(
            ordinal = 0,
            packageName = PACKAGE_NAME,
            displayLabel = "Example",
            deterministicStagingIdentity = stagingIdentity,
            attemptCount = 2,
        ),
        taskAttemptCount = 2,
        resumedFrom = null,
    )

    private fun acceptingCheckpoints() = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED }

    private fun bundle(name: String, content: String): File =
        File(Files.createTempDirectory("share_runner_").toFile().also(temporaryFiles::add), name)
            .apply { writeText(content) }

    private class RecordingShareOperations(
        private val bundle: File? = null,
        private val bundleResult: Result<File>? = null,
        private val buildFailure: CancellationException? = null,
        private val splitPaths: List<String> = emptyList(),
        private val progressScript: suspend (
            VerifiedProgress,
            VerifiedOperationBoundary,
        ) -> Unit = { _, _ -> },
        var incompletePartPresent: Boolean = false,
    ) : SharePrepareTaskOperations {
        val discards = mutableListOf<Pair<String, String>>()
        val builds = mutableListOf<BuildCall>()
        var builtOnlyAfterDiscard = true
        var sideEffectAfterProgress = false

        override suspend fun awaitLaunchSweep(): Boolean = true

        override suspend fun loadApp(packageName: String): AppInfo = AppInfo(
            packageName = packageName,
            appName = "Example",
            versionName = "1.0",
            versionCode = 1L,
            publicSourceDir = "/apps/$packageName/base.apk",
            splitPublicSourceDirs = splitPaths,
        )

        override suspend fun discardIncomplete(
            stagingSubDir: String,
            packageName: String,
        ): Boolean {
            discards += stagingSubDir to packageName
            incompletePartPresent = false
            return true
        }

        override suspend fun buildBundle(
            appInfo: AppInfo,
            cacheSubDir: String,
            format: BundleFormat,
            fileName: String,
            execution: PrivilegeExecutionContext,
            progress: VerifiedProgress,
            operationBoundary: VerifiedOperationBoundary,
        ): Result<File> {
            if (incompletePartPresent) builtOnlyAfterDiscard = false
            builds += BuildCall(cacheSubDir, fileName, format, execution)
            buildFailure?.let { throw it }
            progressScript(progress, operationBoundary)
            sideEffectAfterProgress = true
            return bundleResult ?: Result.success(requireNotNull(bundle))
        }

    }

    private data class BuildCall(
        val stagingSubDir: String,
        val fileName: String,
        val format: BundleFormat,
        val execution: PrivilegeExecutionContext,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val TASK_ID: UUID = UUID.fromString("88888888-8888-8888-8888-888888888888")
    }
}
