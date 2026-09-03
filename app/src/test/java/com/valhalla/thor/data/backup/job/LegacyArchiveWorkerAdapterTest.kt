// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import androidx.work.ListenableWorker
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BACKUP_BUNDLE_KEY
import com.valhalla.thor.domain.model.BACKUP_CLASSES_KEY
import com.valhalla.thor.domain.model.BACKUP_PACKAGE_KEY
import com.valhalla.thor.domain.model.BACKUP_SALT_KEY
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskMessage
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.JOB_WARNINGS_KEY
import com.valhalla.thor.domain.model.RESTORE_CLASSES_KEY
import com.valhalla.thor.domain.model.RESTORE_OBB_KEY
import com.valhalla.thor.domain.model.RESTORE_PACKAGE_KEY
import com.valhalla.thor.domain.model.RESTORE_URI_KEY
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.ThorJobStage
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyArchiveWorkerAdapterTest {

    @Test
    fun `backup decoder preserves every released input and drops only unknown class ids`() {
        val salt = ByteArray(16) { it.toByte() }
        val request = decodeLegacyArchiveBackupRequest(
            mapOf(
                BACKUP_PACKAGE_KEY to PACKAGE_NAME,
                BACKUP_CLASSES_KEY to arrayOf(DataClass.CE.id, "future-class", DataClass.DE.id),
                BACKUP_BUNDLE_KEY to true,
                BACKUP_SALT_KEY to Base64.getEncoder().encodeToString(salt),
            ),
        )

        assertEquals(PACKAGE_NAME, request?.packageName)
        assertEquals(setOf(DataClass.CE, DataClass.DE), request?.classes)
        assertEquals(true, request?.includeBundle)
        assertArrayEquals(salt, request?.salt)
    }

    @Test
    fun `backup decoder defaults the released bundle flag to false and rejects invalid salt`() {
        val base = mapOf<String, Any?>(
            BACKUP_PACKAGE_KEY to PACKAGE_NAME,
            BACKUP_CLASSES_KEY to arrayOf(DataClass.CE.id),
            BACKUP_SALT_KEY to Base64.getEncoder().encodeToString(ByteArray(16)),
        )

        assertFalse(requireNotNull(decodeLegacyArchiveBackupRequest(base)).includeBundle)
        assertEquals(
            null,
            decodeLegacyArchiveBackupRequest(
                base + (BACKUP_SALT_KEY to Base64.getEncoder().encodeToString(ByteArray(15))),
            ),
        )
    }

    @Test
    fun `restore decoder preserves the released raw URI map and ignores invented preflight inputs`() {
        val request = decodeLegacyArchiveRestoreRequest(
            mapOf(
                RESTORE_URI_KEY to "content://provider/archive",
                RESTORE_PACKAGE_KEY to PACKAGE_NAME,
                RESTORE_CLASSES_KEY to arrayOf(DataClass.CE.id, "future-class"),
                RESTORE_OBB_KEY to true,
                "thor.restore.header" to "must-not-be-trusted",
                "thor.restore.install-first" to true,
            ),
        )

        assertEquals(
            ArchiveRestoreRequest(
                uriString = "content://provider/archive",
                packageName = PACKAGE_NAME,
                classes = setOf(DataClass.CE),
                restoreObb = true,
            ),
            request,
        )
    }

    @Test
    fun `restore decoder defaults OBB to false and requires a known selected class`() {
        val base = mapOf<String, Any?>(
            RESTORE_URI_KEY to "content://provider/archive",
            RESTORE_PACKAGE_KEY to PACKAGE_NAME,
            RESTORE_CLASSES_KEY to arrayOf(DataClass.CE.id),
        )

        assertFalse(requireNotNull(decodeLegacyArchiveRestoreRequest(base)).restoreObb)
        assertEquals(
            null,
            decodeLegacyArchiveRestoreRequest(
                base + (RESTORE_CLASSES_KEY to arrayOf("future-class")),
            ),
        )
    }

    @Test
    fun `legacy adapter takes the key before the runner can touch package or source data`() =
        runBlocking {
            val events = mutableListOf<String>()
            val runner = object : DataTaskRunner {
                override val kind = DataTaskKind.ARCHIVE_BACKUP

                override suspend fun run(
                    request: DataTaskExecutionRequest,
                    checkpoints: DataTaskCheckpointSink,
                ): DataTaskRunOutcome {
                    events += "runner:${request.taskId}"
                    return completed()
                }
            }

            runLegacyArchiveTask(
                taskId = TASK_ID,
                requestFactory = { taskId, key ->
                    events += "request:$taskId"
                    backupExecutionRequest(taskId, key)
                },
                takeKey = {
                    events += "take:$it"
                    KEY
                },
                runner = runner,
                checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                results = DataTaskResultSink { outcome ->
                    events += "result"
                    outcome
                },
                missingKeyReason = "missing",
            )

            assertEquals(
                listOf("take:$TASK_ID", "request:$TASK_ID", "runner:$TASK_ID", "result"),
                events,
            )
        }

    @Test
    fun `missing legacy key returns one bounded failure before the runner`() = runBlocking {
        var runnerCalled = false
        val hugeReason = "x".repeat(MAX_JOB_MESSAGE_CHARS + 100)
        val result = runLegacyArchiveTask(
            taskId = TASK_ID,
            requestFactory = { taskId, key -> backupExecutionRequest(taskId, key) },
            takeKey = { null },
            runner = object : DataTaskRunner {
                override val kind = DataTaskKind.ARCHIVE_BACKUP
                override suspend fun run(
                    request: DataTaskExecutionRequest,
                    checkpoints: DataTaskCheckpointSink,
                ): DataTaskRunOutcome {
                    runnerCalled = true
                    return completed()
                }
            },
            checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
            results = LegacyWorkerResultSink(DataTaskKind.ARCHIVE_BACKUP),
            missingKeyReason = hugeReason,
        )

        assertFalse(runnerCalled)
        assertTrue(result is ListenableWorker.Result.Failure)
        val error = result.outputData.getString(JOB_ERROR_KEY)
        assertEquals(MAX_JOB_MESSAGE_CHARS, error?.length)
        assertTrue(error?.endsWith("…") == true)
        assertFalse(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `legacy adapter rethrows cancellation and never translates it to retry`() {
        val cancellation = CancellationException("cancel worker")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runLegacyArchiveTask(
                    taskId = TASK_ID,
                    requestFactory = { taskId, key -> backupExecutionRequest(taskId, key) },
                    takeKey = { KEY },
                    runner = object : DataTaskRunner {
                        override val kind = DataTaskKind.ARCHIVE_BACKUP
                        override suspend fun run(
                            request: DataTaskExecutionRequest,
                            checkpoints: DataTaskCheckpointSink,
                        ): DataTaskRunOutcome = throw cancellation
                    },
                    checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                    results = LegacyWorkerResultSink(DataTaskKind.ARCHIVE_BACKUP),
                    missingKeyReason = "missing",
                )
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `backup success is bare while restore success carries bounded warnings`() = runBlocking {
        val backup = LegacyWorkerResultSink(DataTaskKind.ARCHIVE_BACKUP).persist(
            completed(warnings = listOf("ignored on backup")),
        )
        val restore = LegacyWorkerResultSink(DataTaskKind.ARCHIVE_RESTORE).persist(
            completed(warnings = listOf("w".repeat(MAX_JOB_MESSAGE_CHARS))),
        )

        assertTrue(backup is ListenableWorker.Result.Success)
        assertTrue(backup.outputData.keyValueMap.isEmpty())
        assertTrue(restore is ListenableWorker.Result.Success)
        val warnings = restore.outputData.getStringArray(JOB_WARNINGS_KEY)
        assertEquals(1, warnings?.size)
        assertEquals(MAX_JOB_MESSAGE_CHARS, warnings?.single()?.length)
    }

    @Test
    fun `legacy checkpoint sink translates typed stages without WorkManager progress data`() =
        runBlocking {
            val published = mutableListOf<com.valhalla.thor.domain.model.ThorJobProgress>()
            val sink = LegacyWorkerCheckpointSink(published::add)

            val result = sink.persist(
                com.valhalla.thor.domain.model.DataTaskCheckpoint(
                    stage = DataTaskStage.RESTORING,
                    completed = 3L,
                    total = 7L,
                    activeItemOrdinal = 0,
                    activeItemLabel = "Example",
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = null,
                    recordedAtEpochMs = 5L,
                ),
            )

            assertSame(DataTaskSinkWrite.APPLIED, result)
            assertEquals(ThorJobStage.RESTORING, published.single().stage)
            assertEquals("Example", published.single().label)
            assertEquals(3L, published.single().completed)
            assertEquals(7L, published.single().total)
        }

    private fun backupExecutionRequest(
        taskId: UUID,
        key: javax.crypto.SecretKey,
    ) = DataTaskExecutionRequest(
        taskId = taskId,
        payload = DataTaskExecutionPayload.ArchiveBackup(
            request = ArchiveBackupRequest(
                packageName = PACKAGE_NAME,
                classes = setOf(DataClass.CE),
                includeBundle = false,
                salt = ByteArray(16),
            ),
            key = key,
            destination = StoredDataDestination.ArchiveStore,
        ),
        item = DataTaskExecutionItem(
            ordinal = 0,
            packageName = PACKAGE_NAME,
            displayLabel = "Example",
            deterministicStagingIdentity = taskId.toString(),
            attemptCount = 1,
        ),
        taskAttemptCount = 1,
        resumedFrom = null,
    )

    private fun completed(
        warnings: List<String> = emptyList(),
    ) = DataTaskRunOutcome.ItemCompleted(
        DataTaskItemResult(
            terminalState = DataTaskItemTerminalState.SUCCEEDED,
            resultCode = DataTaskResultCode("ARCHIVE_COMPLETED"),
            warnings = warnings.map {
                DataTaskMessage(DataTaskResultCode("ARCHIVE_WARNING"), listOf(it))
            },
            outputs = emptyList(),
            finishedAtEpochMs = 1L,
        ),
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val TASK_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val KEY = SecretKeySpec(ByteArray(32), "AES")
    }
}
