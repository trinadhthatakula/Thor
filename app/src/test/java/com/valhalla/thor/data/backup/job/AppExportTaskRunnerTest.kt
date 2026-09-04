// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.repository.AppExportPublication
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.AppExportPublicationStatus
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import com.valhalla.thor.domain.usecase.ExportSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExportTaskRunnerTest {

    @Test
    fun `resumed export starts over with the same operation-owned staging and output names`() =
        runBlocking {
            val operations = RecordingExportOperations()
            val runner = AppExportTaskRunner(operations, Dispatchers.Unconfined) { 50L }
            val request = exportRequest(
                resumedFrom = checkpoint(DataTaskStage.PUBLISHING),
                stagingIdentity = "item-$TASK_ID-0",
            )

            val first = runner.run(request, acceptingCheckpoints())
            val second = runner.run(request, acceptingCheckpoints())

            assertSucceeded(first)
            assertSucceeded(second)
            assertEquals(2, operations.exports.size)
            assertEquals(
                listOf("item-$TASK_ID-0", "item-$TASK_ID-0"),
                operations.exports.map { it.session.stagingSubDir },
            )
            assertEquals(
                operations.exports[0].publicationIdentity,
                operations.exports[1].publicationIdentity,
            )
            assertTrue(
                requireNotNull(operations.exports[0].publicationIdentity)
                    .fileName.startsWith("Thor-task-")
            )
            assertEquals(listOf(PACKAGE_NAME, PACKAGE_NAME), operations.loadedPackages)
            assertEquals(2, operations.sweepWaits)
        }

    @Test
    fun `custom destination is revalidated before packaging`() = runBlocking {
        val operations = RecordingExportOperations(treeWritable = false)
        val checkpoints = mutableListOf<DataTaskStage>()
        val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined) { 10L }.run(
            exportRequest(treeUri = "content://provider/tree"),
            DataTaskCheckpointSink {
                checkpoints += it.stage
                DataTaskSinkWrite.APPLIED
            },
        )

        val completed = outcome as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.FAILED, completed.result.terminalState)
        assertEquals("APP_EXPORT_DESTINATION_UNAVAILABLE", completed.result.resultCode.value)
        assertEquals(listOf(DataTaskStage.PREPARING), checkpoints)
        assertEquals(listOf("content://provider/tree"), operations.checkedTrees)
        assertTrue(operations.exports.isEmpty())
    }

    @Test
    fun `legacy staging identity preserves the released one-app export session and name`() =
        runBlocking {
            val operations = RecordingExportOperations()
            val request = exportRequest(stagingIdentity = LEGACY_APP_EXPORT_STAGING_IDENTITY)

            val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined) { 1L }
                .run(request, acceptingCheckpoints())

            assertSucceeded(outcome)
            assertEquals(
                LEGACY_APP_EXPORT_STAGING_IDENTITY,
                operations.exports.single().session.stagingSubDir
            )
            assertEquals(null, operations.exports.single().publicationIdentity)
            assertEquals(ExportTargetChoice.Downloads, operations.exports.single().session.target)
        }

    @Test
    fun `legacy execution forwards verified progress to its process local checkpoint sink`() =
        runBlocking {
            val operations = RecordingExportOperations(
                progressScript = { capture, boundary, publication ->
                    capture.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES)
                    boundary.onOperationCompleted()
                    publication.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES)
                },
            )
            val stages = mutableListOf<DataTaskStage>()

            val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
                exportRequest(stagingIdentity = LEGACY_APP_EXPORT_STAGING_IDENTITY),
                DataTaskCheckpointSink {
                    stages += it.stage
                    DataTaskSinkWrite.APPLIED
                },
            )

            assertSucceeded(outcome)
            assertEquals(null, operations.exports.single().publicationIdentity)
            assertEquals(
                listOf(
                    DataTaskStage.PREPARING,
                    DataTaskStage.CAPTURING,
                    DataTaskStage.CAPTURING,
                    DataTaskStage.PUBLISHING,
                ),
                stages,
            )
        }

    @Test
    fun `traversing staging identity is rejected before any operation`() = runBlocking {
        val operations = RecordingExportOperations()

        val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
            exportRequest(stagingIdentity = ".."),
            acceptingCheckpoints(),
        ) as DataTaskRunOutcome.TaskFailed

        assertEquals("APP_EXPORT_REQUEST_MISMATCH", outcome.resultCode.value)
        assertEquals(0, operations.sweepWaits)
        assertTrue(operations.loadedPackages.isEmpty())
        assertTrue(operations.exports.isEmpty())
    }

    @Test
    fun `durable publication identity ignores re-resolved app label and version`() = runBlocking {
        val firstOperations = RecordingExportOperations(
            resolvedAppName = "Old label",
            resolvedVersionName = "1.0",
        )
        val secondOperations = RecordingExportOperations(
            resolvedAppName = "New label",
            resolvedVersionName = "9.9",
        )
        val request = exportRequest()

        assertSucceeded(
            AppExportTaskRunner(firstOperations, Dispatchers.Unconfined)
                .run(request, acceptingCheckpoints())
        )
        assertSucceeded(
            AppExportTaskRunner(secondOperations, Dispatchers.Unconfined)
                .run(request, acceptingCheckpoints())
        )

        val firstIdentity = requireNotNull(firstOperations.exports.single().publicationIdentity)
        assertEquals(firstIdentity, secondOperations.exports.single().publicationIdentity)
        assertEquals("Thor-task-$TASK_ID-0.apk", firstIdentity.fileName)
        assertFalse(firstIdentity.fileName.contains("Old"))
        assertFalse(firstIdentity.fileName.contains("9.9"))
    }

    @Test
    fun `verified bytes persist throttled capture and publication checkpoints`() = runBlocking {
        val operations = RecordingExportOperations(
            progressScript = { capture, _, publication ->
                capture.onBytesWritten(0L)
                capture.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES / 2)
                capture.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES / 2)
                publication.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES)
            },
        )
        val stages = mutableListOf<DataTaskStage>()

        val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
            exportRequest(),
            DataTaskCheckpointSink {
                stages += it.stage
                DataTaskSinkWrite.APPLIED
            },
        )

        assertSucceeded(outcome)
        assertEquals(
            listOf(
                DataTaskStage.PREPARING,
                DataTaskStage.CAPTURING,
                DataTaskStage.CAPTURING,
                DataTaskStage.PUBLISHING,
            ),
            stages,
        )
    }

    @Test
    fun `continued small writes checkpoint after the monotonic interval`() = runBlocking {
        var monotonicMs = 0L
        val operations = RecordingExportOperations(
            progressScript = { capture, _, _ ->
                capture.onBytesWritten(1L)
                monotonicMs = 60_000L
                capture.onBytesWritten(1L)
            },
        )
        val stages = mutableListOf<DataTaskStage>()

        val outcome = AppExportTaskRunner(
            operations = operations,
            ioDispatcher = Dispatchers.Unconfined,
            monotonicNowMs = { monotonicMs },
        ).run(
            exportRequest(),
            DataTaskCheckpointSink {
                stages += it.stage
                DataTaskSinkWrite.APPLIED
            },
        )

        assertSucceeded(outcome)
        assertEquals(
            listOf(DataTaskStage.PREPARING, DataTaskStage.CAPTURING, DataTaskStage.CAPTURING),
            stages,
        )
    }

    @Test
    fun `verified operation boundary checkpoints immediately and resets byte throttle`() =
        runBlocking {
            val operations = RecordingExportOperations(
                progressScript = { capture, boundary, _ ->
                    capture.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES - 1L)
                    boundary.onOperationCompleted()
                    capture.onBytesWritten(1L)
                },
            )
            val stages = mutableListOf<DataTaskStage>()

            val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
                exportRequest(),
                DataTaskCheckpointSink {
                    stages += it.stage
                    DataTaskSinkWrite.APPLIED
                },
            )

            assertSucceeded(outcome)
            assertEquals(
                listOf(DataTaskStage.PREPARING, DataTaskStage.CAPTURING, DataTaskStage.CAPTURING),
                stages,
            )
        }

    @Test
    fun `ownership loss at operation boundary aborts before later side effects`() = runBlocking {
        val operations = RecordingExportOperations(
            progressScript = { _, boundary, _ -> boundary.onOperationCompleted() },
        )
        var checkpointCount = 0

        val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
            exportRequest(),
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
    fun `ownership loss from verified progress aborts export before later side effects`() =
        runBlocking {
            val operations = RecordingExportOperations(
                progressScript = { capture, _, _ ->
                    capture.onBytesWritten(DATA_TASK_PROGRESS_MIN_BYTES)
                },
            )
            var checkpointCount = 0

            val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
                exportRequest(),
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
    fun `ownership loss at preparing prevents sweep and package work`() = runBlocking {
        val operations = RecordingExportOperations()

        val outcome = AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
            exportRequest(),
            DataTaskCheckpointSink { DataTaskSinkWrite.OWNERSHIP_LOST },
        )

        assertSame(DataTaskRunOutcome.OwnershipLost, outcome)
        assertEquals(0, operations.sweepWaits)
        assertTrue(operations.loadedPackages.isEmpty())
        assertTrue(operations.exports.isEmpty())
    }

    @Test
    fun `export cancellation is rethrown unchanged`() {
        val cancellation = CancellationException("stop export")
        val operations = RecordingExportOperations(exportResult = { throw cancellation })

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                AppExportTaskRunner(operations, Dispatchers.Unconfined)
                    .run(exportRequest(), acceptingCheckpoints())
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `export cancellation returned as a failed result is rethrown unchanged`() {
        val cancellation = CancellationException("stop export")
        val operations = RecordingExportOperations(
            exportResult = { Result.failure(cancellation) },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                AppExportTaskRunner(operations, Dispatchers.Unconfined)
                    .run(exportRequest(), acceptingCheckpoints())
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `successful public export does not retain a private output`() = runBlocking {
        val outcome =
            AppExportTaskRunner(RecordingExportOperations(), Dispatchers.Unconfined) { 9L }
                .run(exportRequest(), acceptingCheckpoints()) as DataTaskRunOutcome.ItemCompleted

        assertEquals(DataTaskItemTerminalState.SUCCEEDED, outcome.result.terminalState)
        assertTrue(outcome.result.outputs.isEmpty())
        assertFalse(outcome.result.outputs.any { it.state == DataTaskOutputState.READY })
    }

    private fun exportRequest(
        treeUri: String? = null,
        stagingIdentity: String = "item-$TASK_ID-0",
        resumedFrom: DataTaskCheckpoint? = null,
    ) = DataTaskExecutionRequest(
        taskId = TASK_ID,
        payload = DataTaskExecutionPayload.AppExport(
            request = AppExportRequest(
                packageName = PACKAGE_NAME,
                format = BundleFormat.APK,
                label = "Example",
                treeUri = treeUri,
            ),
            publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
        ),
        item = DataTaskExecutionItem(
            ordinal = 0,
            packageName = PACKAGE_NAME,
            displayLabel = "Example",
            deterministicStagingIdentity = stagingIdentity,
            attemptCount = 2,
        ),
        taskAttemptCount = 2,
        resumedFrom = resumedFrom,
    )

    private fun checkpoint(stage: DataTaskStage) = DataTaskCheckpoint(
        stage = stage,
        completed = 0,
        total = 1,
        activeItemOrdinal = 0,
        activeItemLabel = "Example",
        destructiveStarted = false,
        restoreMutationBreadcrumb = null,
        recordedAtEpochMs = 1L,
    )

    private fun acceptingCheckpoints() = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED }

    private fun assertSucceeded(outcome: DataTaskRunOutcome) {
        val completed = outcome as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.SUCCEEDED, completed.result.terminalState)
    }

    private class RecordingExportOperations(
        private val treeWritable: Boolean = true,
        private val resolvedAppName: String = "Example",
        private val resolvedVersionName: String = "1.0",
        private val progressScript: suspend (
            VerifiedProgress,
            VerifiedOperationBoundary,
            VerifiedProgress,
        ) -> Unit = { _, _, _ -> },
        private val exportResult: suspend () -> Result<AppExportPublication> = {
            Result.success(
                AppExportPublication(
                    destinationLabel = "Downloads/Thor",
                    status = AppExportPublicationStatus.PUBLISHED,
                )
            )
        },
    ) : AppExportTaskOperations {
        var sweepWaits = 0
        var sideEffectAfterProgress = false
        val loadedPackages = mutableListOf<String>()
        val checkedTrees = mutableListOf<String>()
        val exports = mutableListOf<ExportCall>()

        override suspend fun awaitLaunchSweep(): Boolean {
            sweepWaits++
            return true
        }

        override suspend fun loadApp(packageName: String): AppInfo? {
            loadedPackages += packageName
            return AppInfo(
                packageName = packageName,
                appName = resolvedAppName,
                versionName = resolvedVersionName,
                versionCode = 1L,
                publicSourceDir = "/apps/$packageName/base.apk",
            )
        }

        override suspend fun isTreeWritable(treeUri: String): Boolean {
            checkedTrees += treeUri
            return treeWritable
        }

        override suspend fun exportInto(
            appInfo: AppInfo,
            format: BundleFormat,
            session: ExportSession,
            publicationIdentity: AppExportPublicationIdentity?,
            execution: PrivilegeExecutionContext,
            captureProgress: VerifiedProgress,
            captureBoundary: VerifiedOperationBoundary,
            publicationProgress: VerifiedProgress,
        ): Result<AppExportPublication> {
            exports += ExportCall(session, publicationIdentity, execution)
            progressScript(captureProgress, captureBoundary, publicationProgress)
            sideEffectAfterProgress = true
            return exportResult()
        }
    }

    private data class ExportCall(
        val session: ExportSession,
        val publicationIdentity: AppExportPublicationIdentity?,
        val execution: PrivilegeExecutionContext,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val TASK_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
    }
}
