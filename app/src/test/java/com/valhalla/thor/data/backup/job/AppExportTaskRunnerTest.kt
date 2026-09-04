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
            assertEquals(operations.exports[0].fileName, operations.exports[1].fileName)
            assertTrue(requireNotNull(operations.exports[0].fileName).contains(PACKAGE_NAME))
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
            assertEquals(null, operations.exports.single().fileName)
            assertEquals(ExportTargetChoice.Downloads, operations.exports.single().session.target)
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
        private val exportResult: suspend () -> Result<String> = { Result.success("Downloads/Thor") },
    ) : AppExportTaskOperations {
        var sweepWaits = 0
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
                appName = "Example",
                versionName = "1.0",
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
            fileName: String?,
            execution: PrivilegeExecutionContext,
        ): Result<String> {
            exports += ExportCall(session, fileName, execution)
            return exportResult()
        }
    }

    private data class ExportCall(
        val session: ExportSession,
        val fileName: String?,
        val execution: PrivilegeExecutionContext,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val TASK_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
    }
}
