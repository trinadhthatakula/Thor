// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskMessage
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.EXPORT_FORMAT_KEY
import com.valhalla.thor.domain.model.EXPORT_LABEL_KEY
import com.valhalla.thor.domain.model.EXPORT_PACKAGE_KEY
import com.valhalla.thor.domain.model.EXPORT_TREE_KEY
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAppExportWorkerAdapterTest {

    @Test
    fun `released export decoder keeps exact format and transient raw tree URI`() {
        val rawTree = "content://provider/tree/primary%3ADocuments"

        val request = decodeLegacyAppExportRequest(
            mapOf(
                EXPORT_PACKAGE_KEY to PACKAGE_NAME,
                EXPORT_FORMAT_KEY to BundleFormat.XAPK.name,
                EXPORT_LABEL_KEY to "Example",
                EXPORT_TREE_KEY to rawTree,
            )
        )

        assertEquals(PACKAGE_NAME, request?.packageName)
        assertEquals(BundleFormat.XAPK, request?.format)
        assertEquals("Example", request?.label)
        assertEquals(rawTree, request?.treeUri)
    }

    @Test
    fun `released decoder rejects blank and unknown required values without format fallback`() {
        val valid = mapOf<String, Any?>(
            EXPORT_PACKAGE_KEY to PACKAGE_NAME,
            EXPORT_FORMAT_KEY to BundleFormat.APK.name,
            EXPORT_LABEL_KEY to "Example",
        )

        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_PACKAGE_KEY to " ")))
        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_LABEL_KEY to "")))
        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_FORMAT_KEY to "apk")))
        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_FORMAT_KEY to "FUTURE")))
        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_FORMAT_KEY to 1)))
        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_TREE_KEY to " ")))
        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_TREE_KEY to 1)))
        assertEquals(null, decodeLegacyAppExportRequest(valid + (EXPORT_PACKAGE_KEY to 1)))
    }

    @Test
    fun `legacy progress retains the released localized labels and counters`() {
        val original = ThorJobProgress(
            stage = ThorJobStage.CAPTURING,
            label = "Example",
            completed = 2,
            total = 5,
        )

        val decorated = legacyAppExportProgress(
            progress = original,
            preparingLabel = "Preparing export of Example",
            packagingLabel = "Packaging Example as APK",
        )

        assertEquals(ThorJobStage.CAPTURING, decorated.stage)
        assertEquals("Packaging Example as APK", decorated.label)
        assertEquals(2, decorated.completed)
        assertEquals(5, decorated.total)
    }

    @Test
    fun `malformed released work fails before the runner and never retries`() = runBlocking {
        var runnerCalled = false

        val result = runLegacyAppExportTask(
            taskId = TASK_ID,
                packages = com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator(),
            decodedRequest = null,
            runAttemptCount = 4,
            invalidRequestReason = "this export's request could not be read",
            runner = object : DataTaskRunner {
                override val kind = DataTaskKind.APP_EXPORT
                override suspend fun run(
                    request: DataTaskExecutionRequest,
                    checkpoints: DataTaskCheckpointSink,
                ): DataTaskRunOutcome {
                    runnerCalled = true
                    return successfulItem()
                }
            },
            checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
            results = LegacyWorkerResultSink(DataTaskKind.APP_EXPORT),
        )

        assertFalse(runnerCalled)
        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(JOB_ERROR_KEY to "this export's request could not be read")
            ),
            result,
        )
    }

    @Test
    fun `adapter builds one synthetic public-document item without copying the tree elsewhere`() =
        runBlocking {
            val rawTree = "content://provider/tree/primary%3ADocuments"
            val decoded = AppExportRequest(
                packageName = PACKAGE_NAME,
                format = BundleFormat.APK,
                label = "Example",
                treeUri = rawTree,
            )
            var captured: DataTaskExecutionRequest? = null

            val result = runLegacyAppExportTask(
                taskId = TASK_ID,
                packages = com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator(),
                decodedRequest = decoded,
                runAttemptCount = 7,
                invalidRequestReason = "invalid",
                runner = object : DataTaskRunner {
                    override val kind = DataTaskKind.APP_EXPORT
                    override suspend fun run(
                        request: DataTaskExecutionRequest,
                        checkpoints: DataTaskCheckpointSink,
                    ): DataTaskRunOutcome {
                        captured = request
                        return successfulItem()
                    }
                },
                checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                results = LegacyWorkerResultSink(DataTaskKind.APP_EXPORT),
            )

            assertEquals(ListenableWorker.Result.success(), result)
            val request = requireNotNull(captured)
            val payload = request.payload as DataTaskExecutionPayload.AppExport
            assertEquals(TASK_ID, request.taskId)
            assertEquals(0, request.item.ordinal)
            assertEquals(7, request.item.attemptCount)
            assertEquals(
                LEGACY_APP_EXPORT_STAGING_IDENTITY,
                request.item.deterministicStagingIdentity
            )
            assertEquals(DataTaskPublicationPolicy.PUBLIC_DOCUMENT, payload.publicationPolicy)
            assertSame(decoded, payload.request)
            assertEquals(rawTree, payload.request.treeUri)
        }

    @Test
    fun `failed item preserves its bounded actionable reason in legacy output data`() =
        runBlocking {
            val hugeReason = "not enough space ".repeat(2_000)

            val result = runLegacyAppExportTask(
                taskId = TASK_ID,
                packages = com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator(),
                decodedRequest = request(),
                runAttemptCount = 1,
                invalidRequestReason = "invalid",
                runner = fixedRunner(failedItem(hugeReason)),
                checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                results = LegacyWorkerResultSink(DataTaskKind.APP_EXPORT),
            )

            val expected = hugeReason.take(MAX_JOB_MESSAGE_CHARS - 1) + "…"
            assertEquals(
                ListenableWorker.Result.failure(workDataOf(JOB_ERROR_KEY to expected)),
                result,
            )
        }

    @Test
    fun `adapter rethrows cancellation and does not persist a worker result`() {
        val cancellation = CancellationException("stop worker")
        var persisted = false

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runLegacyAppExportTask(
                    taskId = TASK_ID,
                packages = com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator(),
                    decodedRequest = request(),
                    runAttemptCount = 1,
                    invalidRequestReason = "invalid",
                    runner = object : DataTaskRunner {
                        override val kind = DataTaskKind.APP_EXPORT
                        override suspend fun run(
                            request: DataTaskExecutionRequest,
                            checkpoints: DataTaskCheckpointSink,
                        ): DataTaskRunOutcome = throw cancellation
                    },
                    checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                    results = DataTaskResultSink {
                        persisted = true
                        ListenableWorker.Result.retry()
                    },
                )
            }
        }

        assertSame(cancellation, thrown)
        assertFalse(persisted)
    }

    @Test
    fun `terminal callbacks match the public worker result exactly once`() = runBlocking {
        val events = mutableListOf<String>()
        val sink = LegacyWorkerResultSink(
            DataTaskKind.APP_EXPORT,
            onSuccess = { events += "saved" },
            onFailure = { events += it },
        )
        assertEquals(ListenableWorker.Result.success(), sink.persist(successfulItem()))
        assertEquals(listOf("saved"), events)
        events.clear()

        // Typed outcomes already reject oversized arguments; retain their bounded failure verbatim.
        val bounded = "x".repeat(MAX_JOB_MESSAGE_CHARS - 1) + "…"
        val outcome = DataTaskRunOutcome.TaskFailed(
            DataTaskResultCode("APP_EXPORT_FAILED"), listOf(bounded),
        )
        assertEquals(
            ListenableWorker.Result.failure(workDataOf(JOB_ERROR_KEY to bounded)),
            sink.persist(outcome),
        )
        assertEquals(listOf(bounded), events)
    }

    @Test
    fun `malformed and mapped failed exports notify without reading worker internals`() = runBlocking {
        for ((decoded, outcome, expected) in listOf(
            Triple(null, successfulItem(), "invalid request"),
            Triple(request(), failedItem("no space"), "localized: no space"),
        )) {
            val events = mutableListOf<String>()
            val result = runLegacyAppExportTask(
                taskId = TASK_ID,
                packages = com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator(),
                decodedRequest = decoded,
                runAttemptCount = 1,
                invalidRequestReason = "invalid request",
                runner = fixedRunner(outcome),
                checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                results = LegacyWorkerResultSink(
                    DataTaskKind.APP_EXPORT,
                    onSuccess = { events += "unexpected success" },
                    onFailure = { events += it },
                ),
                failureReason = { _, detail -> "localized: $detail" },
            )
            assertEquals(
                ListenableWorker.Result.failure(workDataOf(JOB_ERROR_KEY to expected)),
                result,
            )
            assertEquals(listOf(expected), events)
        }
    }

    private fun request() = AppExportRequest(
        packageName = PACKAGE_NAME,
        format = BundleFormat.APK,
        label = "Example",
    )

    private fun fixedRunner(outcome: DataTaskRunOutcome) = object : DataTaskRunner {
        override val kind = DataTaskKind.APP_EXPORT
        override suspend fun run(
            request: DataTaskExecutionRequest,
            checkpoints: DataTaskCheckpointSink,
        ): DataTaskRunOutcome = outcome
    }

    internal fun successfulItem() = DataTaskRunOutcome.ItemCompleted(
        DataTaskItemResult(
            terminalState = DataTaskItemTerminalState.SUCCEEDED,
            resultCode = DataTaskResultCode("APP_EXPORT_COMPLETED"),
            warnings = emptyList(),
            outputs = emptyList(),
            finishedAtEpochMs = 1L,
        )
    )

    private fun failedItem(reason: String) = DataTaskRunOutcome.ItemCompleted(
        DataTaskItemResult(
            terminalState = DataTaskItemTerminalState.FAILED,
            resultCode = DataTaskResultCode("APP_EXPORT_FAILED"),
            warnings = listOf(
                DataTaskMessage(
                    DataTaskResultCode("APP_EXPORT_FAILURE_REASON"),
                    listOf(reason.boundedForJobData()),
                )
            ),
            outputs = emptyList(),
            finishedAtEpochMs = 1L,
        )
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val TASK_ID: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")
    }
}
