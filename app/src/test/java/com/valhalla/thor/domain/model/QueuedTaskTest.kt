// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
                    TaskActionRequirement.PreparedShare(listOf(java.util.UUID.randomUUID())),
            TaskLifecyclePhase.READY_PARTIAL to
                    TaskActionRequirement.PreparedShare(listOf(java.util.UUID.randomUUID())),
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
                TaskActionRequirement.PreparedShare(listOf(java.util.UUID.randomUUID())),
            ).isEmpty()
        )
    }
}
