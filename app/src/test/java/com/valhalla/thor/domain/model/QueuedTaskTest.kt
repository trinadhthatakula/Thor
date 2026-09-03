// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QueuedTaskTest {

    private val unknownSweepRequirement = TaskActionRequirement.SweepRetryAuthorization(
        targetOrdinal = 0,
        packageName = "pkg",
        operation = PrivilegeSweepOperation.CLEAR_CACHE,
    )

    @Test
    fun actionRequiredIsNeverAcknowledgable() {
        val actions = TaskActionPolicy.actionsFor(
            phase = TaskLifecyclePhase.WAITING_FOR_AUTH,
            requirement = TaskActionRequirement.ArchiveAuthentication(
                "pkg",
                DataTaskKind.ARCHIVE_RESTORE,
            ),
        )

        assertFalse(TaskAction.ACKNOWLEDGE in actions)
        assertTrue(TaskAction.AUTHENTICATE_ARCHIVE in actions)
    }

    @Test
    fun privilegeAndUnknownSweepActionsAreDistinct() {
        assertEquals(
            setOf(TaskAction.AUTHORIZE_PRIVILEGE),
            TaskActionPolicy.actionsFor(
                TaskLifecyclePhase.WAITING_FOR_PRIVILEGE,
                TaskActionRequirement.PrivilegeAuthorization,
            ),
        )
        assertEquals(
            setOf(TaskAction.AUTHORIZE_SWEEP_RETRY),
            TaskActionPolicy.actionsFor(
                TaskLifecyclePhase.INTERRUPTED_REVIEW,
                unknownSweepRequirement,
            ),
        )
    }

    @Test
    fun expiredShareCanOnlyBeAcknowledged() {
        assertEquals(
            setOf(TaskAction.ACKNOWLEDGE),
            TaskActionPolicy.actionsFor(TaskLifecyclePhase.EXPIRED, null),
        )
    }

    @Test
    fun provisionalAndObserverPhasesArePresentationOnly() {
        assertFalse(TaskLifecyclePhase.STARTING.isPersistable)
        assertFalse(TaskLifecyclePhase.OBSERVER_FAILURE.isPersistable)
        assertEquals(
            setOf(TaskLifecyclePhase.STARTING, TaskLifecyclePhase.OBSERVER_FAILURE),
            TaskLifecyclePhase.entries.filterNot { it.isPersistable }.toSet(),
        )
    }

    @Test
    fun queueKindDoesNotDefineCrossQueueOrder() {
        assertNotEquals(TaskQueueKind.DATA, TaskQueueKind.PRIVILEGE)
    }

    @Test
    fun everyActionRequiredPhaseNeedsItsMatchingTypedRequirement() {
        val matching = mapOf(
            TaskLifecyclePhase.WAITING_FOR_AUTH to
                    TaskActionRequirement.ArchiveAuthentication(
                        "pkg",
                        DataTaskKind.ARCHIVE_RESTORE
                    ),
            TaskLifecyclePhase.WAITING_FOR_SOURCE to
                    TaskActionRequirement.RestoreSource("pkg"),
            TaskLifecyclePhase.WAITING_FOR_PRIVILEGE to
                    TaskActionRequirement.PrivilegeAuthorization,
            TaskLifecyclePhase.INTERRUPTED_REVIEW to
                    TaskActionRequirement.RestoreInterruptionReview(
                        RestoreMutationBreadcrumb("pkg", "App", 1L),
                    ),
            TaskLifecyclePhase.READY to
                    TaskActionRequirement.PreparedShare(listOf(UUID.randomUUID())),
            TaskLifecyclePhase.READY_PARTIAL to
                    TaskActionRequirement.PreparedShare(listOf(UUID.randomUUID())),
            TaskLifecyclePhase.START_BLOCKED_NOTIFICATION to
                    TaskActionRequirement.NotificationSettings("channel"),
        )

        for ((phase, requirement) in matching) {
            assertTrue(
                "$phase should expose its typed action",
                TaskActionPolicy.actionsFor(phase, requirement).isNotEmpty()
            )
            assertEquals(
                "$phase must reject a missing requirement",
                emptySet<TaskAction>(),
                TaskActionPolicy.actionsFor(phase, null),
            )
        }
    }

    @Test
    fun activeAndSettlingPhasesHaveDifferentCancellationSurfaces() {
        assertEquals(
            setOf(TaskAction.CANCEL),
            TaskActionPolicy.actionsFor(TaskLifecyclePhase.QUEUED, null),
        )
        assertEquals(
            setOf(TaskAction.CANCEL),
            TaskActionPolicy.actionsFor(TaskLifecyclePhase.RUNNING, null),
        )
        assertTrue(
            TaskActionPolicy.actionsFor(TaskLifecyclePhase.STOPPING, null).isEmpty()
        )
    }

    @Test
    fun policyRejectsMismatchedRequirementTypes() {
        assertTrue(
            TaskActionPolicy.actionsFor(
                TaskLifecyclePhase.WAITING_FOR_AUTH,
                TaskActionRequirement.PrivilegeAuthorization,
            ).isEmpty()
        )
        assertTrue(
            TaskActionPolicy.actionsFor(
                TaskLifecyclePhase.READY,
                TaskActionRequirement.RestoreSource("pkg"),
            ).isEmpty()
        )
        assertTrue(
            TaskActionPolicy.actionsFor(
                TaskLifecyclePhase.START_BLOCKED_NOTIFICATION,
                TaskActionRequirement.PreparedShare(listOf(UUID.randomUUID())),
            ).isEmpty()
        )
    }

    @Test
    fun opaqueGrantIdentitiesAcceptTheBoundaryAndRejectLocationText() {
        val boundary = "a".repeat(128)
        StoredDataDestination.PersistedTreeGrant(boundary)
        StoredRestoreSource.PersistedGrant(boundary)

        val rejected = listOf(
            "",
            "a".repeat(129),
            "content://provider/tree/root",
            "/storage/emulated/0",
            "../grant",
            "grant/../identity",
            "grant\nidentity",
        )
        for (value in rejected) {
            assertThrows(IllegalArgumentException::class.java) {
                StoredDataDestination.PersistedTreeGrant(value)
            }
            assertThrows(IllegalArgumentException::class.java) {
                StoredRestoreSource.PersistedGrant(value)
            }
        }
    }

    @Test
    fun privatePathsAcceptNormalizedRelativeValuesAndRejectLocationEscapes() {
        StoredRestoreSource.PrivateCopy("items/0/archive.thor")
        output(privateRelativePath = "items/0/export.apks")

        val rejected = listOf(
            "",
            "content://provider/document/archive",
            "/data/local/tmp/archive",
            "C:/Users/archive",
            "../archive",
            "items/../archive",
            "items\\archive",
            "items//archive",
            "./archive",
            "items/archive/",
            "items/\u0000archive",
        )
        for (value in rejected) {
            assertThrows(IllegalArgumentException::class.java) {
                StoredRestoreSource.PrivateCopy(value)
            }
            assertThrows(IllegalArgumentException::class.java) {
                output(privateRelativePath = value)
            }
        }
    }

    @Test
    fun presentationArgumentsAcceptTheirCountAndLengthBoundaries() {
        val boundary = List(8) { "a".repeat(512) }

        DataTaskMessage(RESULT_CODE, boundary)
        DataTaskRunOutcome.TaskFailed(RESULT_CODE, boundary)
        TaskLogLine(order = 0, messageCode = "TASK_LOG", arguments = boundary)
        summary(titleArguments = boundary)
    }

    @Test
    fun presentationArgumentCollectionsRejectValuesBeyondTheirBoundaries() {
        val tooMany = List(9) { "safe" }
        val tooLong = "a".repeat(513)

        assertThrows(IllegalArgumentException::class.java) { DataTaskMessage(RESULT_CODE, tooMany) }
        assertThrows(IllegalArgumentException::class.java) {
            DataTaskRunOutcome.TaskFailed(RESULT_CODE, tooMany)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskLogLine(order = 0, messageCode = "TASK_LOG", arguments = tooMany)
        }
        assertThrows(IllegalArgumentException::class.java) { summary(titleArguments = tooMany) }
        assertEveryArgumentContractRejects(tooLong)
    }

    @Test
    fun presentationArgumentsRejectControlUrisPathsAndRawDiagnostics() {
        listOf(
            "line one\nline two",
            "content://provider/document/archive",
            "/data/local/tmp/archive",
            "java.lang.IllegalStateException: boom",
            "at com.example.Runner.run(Runner.kt:42)",
        ).forEach(::assertEveryArgumentContractRejects)
    }

    @Test
    fun warningCollectionsAcceptTheirBoundaryAndRejectOverflow() {
        val warnings = List(4) { DataTaskMessage(DataTaskResultCode("WARNING_$it")) }
        itemResult(warnings)
        detail(warningCodes = List(4) { "WARNING_$it" })

        assertThrows(IllegalArgumentException::class.java) {
            itemResult(List(5) { DataTaskMessage(DataTaskResultCode("WARNING_$it")) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            detail(warningCodes = List(5) { "WARNING_$it" })
        }
    }

    @Test
    fun projectionCodesAcceptTheirLengthBoundaryAndRejectRawDiagnosticPayloads() {
        val boundary = "A".repeat(64)
        TaskLogLine(order = 0, messageCode = boundary)
        detail(resultCode = boundary, warningCodes = listOf(boundary))

        val rejected = listOf(
            "A".repeat(65),
            "java.lang.IllegalStateException: boom",
        )
        for (value in rejected) {
            assertThrows(IllegalArgumentException::class.java) {
                TaskLogLine(order = 0, messageCode = value)
            }
            assertThrows(IllegalArgumentException::class.java) {
                detail(resultCode = value)
            }
            assertThrows(IllegalArgumentException::class.java) {
                detail(warningCodes = listOf(value))
            }
        }
    }

    @Test
    fun progressAcceptsZeroUnknownTotalsAndTheCompletedBoundary() {
        TaskProgress(completed = 0, total = 0, stageLabel = null)
        TaskProgress(completed = 3, total = 0, stageLabel = null)
        TaskProgress(completed = 3, total = 3, stageLabel = null)
        checkpoint(completed = 0, total = 0)
        checkpoint(completed = 3, total = 0)
        checkpoint(completed = 3, total = 3)
    }

    @Test
    fun progressRejectsNegativeCountsAndCompletedBeyondPositiveTotal() {
        val rejected = listOf(
            -1L to 0L,
            0L to -1L,
            2L to 1L,
        )
        for ((completed, total) in rejected) {
            assertThrows(IllegalArgumentException::class.java) {
                TaskProgress(completed, total, null)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkpoint(completed, total)
            }
        }
    }

    @Test
    fun outputSizesAcceptZeroAndRejectNegativeValues() {
        output(byteSize = 0)
        assertThrows(IllegalArgumentException::class.java) { output(byteSize = -1) }
    }

    @Test
    fun ordinalsAndProjectionOrderAcceptZeroAndRejectNegativeValues() {
        TaskActionRequirement.SweepRetryAuthorization(
            targetOrdinal = 0,
            packageName = "pkg",
            operation = PrivilegeSweepOperation.CLEAR_CACHE,
        )
        TaskLogLine(order = 0, messageCode = "TASK_LOG")
        summary(sequence = 0)
        checkpoint(completed = 0, total = 0, activeItemOrdinal = 0)

        assertThrows(IllegalArgumentException::class.java) {
            TaskActionRequirement.SweepRetryAuthorization(
                targetOrdinal = -1,
                packageName = "pkg",
                operation = PrivilegeSweepOperation.CLEAR_CACHE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskLogLine(order = -1, messageCode = "TASK_LOG")
        }
        assertThrows(IllegalArgumentException::class.java) { summary(sequence = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            checkpoint(completed = 0, total = 0, activeItemOrdinal = -1)
        }
    }

    private fun assertEveryArgumentContractRejects(argument: String) {
        assertThrows(IllegalArgumentException::class.java) {
            DataTaskMessage(RESULT_CODE, listOf(argument))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataTaskRunOutcome.TaskFailed(RESULT_CODE, listOf(argument))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskLogLine(order = 0, messageCode = "TASK_LOG", arguments = listOf(argument))
        }
        assertThrows(IllegalArgumentException::class.java) {
            summary(titleArguments = listOf(argument))
        }
    }

    private fun checkpoint(
        completed: Long,
        total: Long,
        activeItemOrdinal: Int? = null,
    ) = DataTaskCheckpoint(
        stage = DataTaskStage.PREPARING,
        completed = completed,
        total = total,
        activeItemOrdinal = activeItemOrdinal,
        activeItemLabel = null,
        destructiveStarted = false,
        restoreMutationBreadcrumb = null,
        recordedAtEpochMs = 1,
    )

    private fun output(
        byteSize: Long = 0,
        privateRelativePath: String? = null,
    ) = NewDataTaskOutput(
        outputId = UUID.randomUUID(),
        privateRelativePath = privateRelativePath,
        displayName = "output.apks",
        mimeType = "application/octet-stream",
        byteSize = byteSize,
        state = DataTaskOutputState.STAGING,
        expiresAtEpochMs = null,
    )

    private fun itemResult(warnings: List<DataTaskMessage>) = DataTaskItemResult(
        terminalState = DataTaskItemTerminalState.SUCCEEDED,
        resultCode = RESULT_CODE,
        warnings = warnings,
        outputs = emptyList(),
        finishedAtEpochMs = 1,
    )

    private fun summary(
        titleArguments: List<String> = emptyList(),
        sequence: Long = 0,
    ) = QueuedTaskSummary(
        taskId = UUID.randomUUID(),
        queueKind = TaskQueueKind.DATA,
        operationId = "TASK_OPERATION",
        titleArguments = titleArguments,
        sequence = sequence,
        phase = TaskLifecyclePhase.QUEUED,
        progress = TaskProgress(0, 0, null),
        activeItemLabel = null,
        actionRequirement = null,
        actions = setOf(TaskAction.CANCEL),
        terminalAtEpochMs = null,
        retainUntilEpochMs = null,
        rootLaneDegraded = false,
    )

    private fun detail(
        resultCode: String? = null,
        warningCodes: List<String> = emptyList(),
    ) = QueuedTaskDetail(
        summary = summary(),
        lines = emptyList(),
        resultCode = resultCode,
        warningCodes = warningCodes,
    )

    private companion object {
        val RESULT_CODE = DataTaskResultCode("OK")
    }
}
