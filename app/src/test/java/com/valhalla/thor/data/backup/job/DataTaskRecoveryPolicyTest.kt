// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import androidx.core.net.toUri
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataDestination
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DataTaskRecoveryPolicyTest {

    @Test
    fun `a missing archive key pauses the durable task for authentication`() {
        assertEquals(
            DataTaskRunOutcome.WaitingForAuthentication(
                DataTaskResultCode("ARCHIVE_AUTHENTICATION_REQUIRED"),
            ),
            archiveKeyUnavailableOutcome(),
        )
    }

    @Test
    fun `a pre-mutation checkpoint permits a fresh authenticated run`() {
        assertNull(recoveryOutcomeFor(backupRequest(checkpoint(destructiveStarted = false))))
    }

    @Test
    fun `a destructive checkpoint requires review and retains its breadcrumb`() {
        val breadcrumb = RestoreMutationBreadcrumb(
            packageName = PACKAGE_NAME,
            appLabel = "Example",
            startedAtEpochMs = 41L,
        )
        val outcome = recoveryOutcomeFor(
            restoreRequest(
                checkpoint(
                    destructiveStarted = true,
                    breadcrumb = breadcrumb,
                ),
            ),
        )

        assertEquals(
            DataTaskRunOutcome.InterruptedReview(
                resultCode = DataTaskResultCode("ARCHIVE_RESTORE_INTERRUPTED"),
                breadcrumb = breadcrumb,
            ),
            outcome,
        )
    }

    @Test
    fun `cancellation settles and cleans up in NonCancellable before it is rethrown`() {
        val events = mutableListOf<String>()
        val cancellation = CancellationException("stop this task")
        val runner = object : DataTaskRunner {
            override val kind = DataTaskKind.ARCHIVE_RESTORE

            override suspend fun run(
                request: DataTaskExecutionRequest,
                checkpoints: DataTaskCheckpointSink,
            ): DataTaskRunOutcome {
                events += "run"
                currentCoroutineContext().cancel(cancellation)
                throw cancellation
            }
        }
        val resultSink = DataTaskResultSink<Unit> { outcome ->
            assertEquals(
                DataTaskRunOutcome.WaitingForAuthentication(
                    DataTaskResultCode("ARCHIVE_AUTHENTICATION_REQUIRED"),
                ),
                outcome,
            )
            assertTrue(currentCoroutineContext().isActive)
            events += "settle"
        }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runDataTaskAndPersist(
                    runner = runner,
                    request = restoreRequest(),
                    checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                    results = resultSink,
                    cleanup = {
                        assertTrue(currentCoroutineContext().isActive)
                        events += "cleanup"
                    },
                )
            }
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf("run", "settle", "cleanup"), events)
    }

    @Test
    fun `destructive cancellation settles review before cleanup and rethrow`() {
        val breadcrumb = RestoreMutationBreadcrumb(PACKAGE_NAME, "Example", 73L)
        val destructive = checkpoint(destructiveStarted = true, breadcrumb = breadcrumb)
        val persisted = mutableListOf<DataTaskRunOutcome>()
        val cancellation = CancellationException("service timeout")
        val runner = object : DataTaskRunner {
            override val kind = DataTaskKind.ARCHIVE_RESTORE

            override suspend fun run(
                request: DataTaskExecutionRequest,
                checkpoints: DataTaskCheckpointSink,
            ): DataTaskRunOutcome {
                assertEquals(DataTaskSinkWrite.APPLIED, checkpoints.persist(destructive))
                throw cancellation
            }
        }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runDataTaskAndPersist(
                    runner = runner,
                    request = restoreRequest(),
                    checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED },
                    results = DataTaskResultSink { outcome -> persisted += outcome },
                    cleanup = {},
                )
            }
        }

        assertSame(cancellation, thrown)
        assertEquals(
            listOf(
                DataTaskRunOutcome.InterruptedReview(
                    DataTaskResultCode("ARCHIVE_RESTORE_INTERRUPTED"),
                    breadcrumb,
                ),
            ),
            persisted,
        )
    }

    @Test
    fun `ownership loss from the runner is persisted without translation`() = runBlocking {
        val runner = object : DataTaskRunner {
            override val kind = DataTaskKind.ARCHIVE_BACKUP

            override suspend fun run(
                request: DataTaskExecutionRequest,
                checkpoints: DataTaskCheckpointSink,
            ): DataTaskRunOutcome {
                assertEquals(
                    DataTaskSinkWrite.OWNERSHIP_LOST,
                    checkpoints.persist(checkpoint(destructiveStarted = false)),
                )
                return DataTaskRunOutcome.OwnershipLost
            }
        }
        val persisted = mutableListOf<DataTaskRunOutcome>()

        val result = runDataTaskAndPersist(
            runner = runner,
            request = backupRequest(),
            checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.OWNERSHIP_LOST },
            results = DataTaskResultSink { outcome ->
                persisted += outcome
                DataTaskSinkWrite.APPLIED
            },
            cleanup = {},
        )

        assertEquals(DataTaskSinkWrite.APPLIED, result)
        assertEquals(listOf(DataTaskRunOutcome.OwnershipLost), persisted)
    }

    @Test
    fun `restore source grants are one shot and task scoped`() {
        val holder = RestoreSourceGrantHolder()
        val firstTask = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val secondTask = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val firstUri = "content://provider/first".toUri()
        val secondUri = "content://provider/second".toUri()
        val firstToken = holder.register(firstTask, firstUri)
        val secondToken = holder.register(secondTask, secondUri)

        assertNull(holder.take(secondTask, firstToken))
        assertEquals(firstUri, holder.take(firstTask, firstToken))
        assertNull(holder.take(firstTask, firstToken))

        holder.dropTask(secondTask)
        assertNull(holder.take(secondTask, secondToken))
    }

    private fun backupRequest(
        resumedFrom: DataTaskCheckpoint? = null,
    ) = DataTaskExecutionRequest(
        taskId = TASK_ID,
        payload = DataTaskExecutionPayload.ArchiveBackup(
            request = ArchiveBackupRequest(
                packageName = PACKAGE_NAME,
                classes = setOf(DataClass.CE),
                includeBundle = false,
                salt = ByteArray(16),
            ),
            key = SecretKeySpec(ByteArray(32), "AES"),
            destination = StoredDataDestination.ArchiveStore,
        ),
        item = DataTaskExecutionItem(
            ordinal = 0,
            packageName = PACKAGE_NAME,
            displayLabel = "Example",
            deterministicStagingIdentity = TASK_ID.toString(),
            attemptCount = 1,
        ),
        taskAttemptCount = 1,
        resumedFrom = resumedFrom,
    )

    private fun restoreRequest(
        resumedFrom: DataTaskCheckpoint? = null,
    ) = DataTaskExecutionRequest(
        taskId = TASK_ID,
        payload = DataTaskExecutionPayload.ArchiveRestore(
            request = ArchiveRestoreRequest(
                uriString = "content://provider/archive",
                packageName = PACKAGE_NAME,
                classes = setOf(DataClass.CE),
                restoreObb = false,
            ),
            key = SecretKeySpec(ByteArray(32), "AES"),
        ),
        item = DataTaskExecutionItem(
            ordinal = 0,
            packageName = PACKAGE_NAME,
            displayLabel = "Example",
            deterministicStagingIdentity = TASK_ID.toString(),
            attemptCount = 1,
        ),
        taskAttemptCount = 1,
        resumedFrom = resumedFrom,
    )

    private fun checkpoint(
        destructiveStarted: Boolean,
        breadcrumb: RestoreMutationBreadcrumb? = null,
    ) = DataTaskCheckpoint(
        stage = DataTaskStage.RESTORING,
        completed = 0L,
        total = 1L,
        activeItemOrdinal = 0,
        activeItemLabel = "Example",
        destructiveStarted = destructiveStarted,
        restoreMutationBreadcrumb = breadcrumb,
        recordedAtEpochMs = 1L,
    )

    private companion object {
        val TASK_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val PACKAGE_NAME = "com.example.app"
    }
}
