// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskUiRoute
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskActionRouteStateTest {

    @Test
    fun `external owner keys are unique per state and stable across recreation`() {
        val firstState = TaskActionRouteState()
        val secondState = TaskActionRouteState()
        val first = firstState.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))
        val second = secondState.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))

        assertTrue(firstState.externalResultKey(first) != secondState.externalResultKey(second))

        val restored = requireNotNull(restoreTaskActionRouteState(firstState.savedValues()))
        assertEquals(
            firstState.externalResultKey(first),
            restored.externalResultKey(requireNotNull(restored.active)),
        )
    }

    @Test
    fun `equal replacement gets a new generation and old completion cannot close it`() {
        val state = TaskActionRouteState()
        val route = TaskUiRoute.AuthorizePrivilege(TASK_ID)
        val first = state.activate(route)
        val replacement = state.activate(route)

        assertTrue(replacement.generation > first.generation)
        state.complete(first, TaskActionDispatch.Applied)
        assertSame(replacement, state.active)
    }

    @Test
    fun `external result must match active generation and kind and can be claimed once`() {
        val state = TaskActionRouteState()
        val activation = state.activate(TaskUiRoute.PickRestoreSource(TASK_ID, "example.app"))

        assertTrue(state.arm(activation, TaskExternalResultKind.RESTORE_SOURCE))
        assertNull(state.claim(activation, TaskExternalResultKind.PRIVILEGE_MANAGER))
        assertSame(activation, state.claim(activation, TaskExternalResultKind.RESTORE_SOURCE))
        assertNull(state.claim(activation, TaskExternalResultKind.RESTORE_SOURCE))
    }

    @Test
    fun `claimed external result remains armed for restoration and can be retried after release`() {
        val state = TaskActionRouteState()
        val activation = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))

        assertTrue(state.arm(activation, TaskExternalResultKind.DHIZUKU_PERMISSION))
        assertSame(
            activation,
            state.claimReplayable(activation, TaskExternalResultKind.DHIZUKU_PERMISSION),
        )
        assertNull(state.claimReplayable(activation, TaskExternalResultKind.DHIZUKU_PERMISSION))

        val restored = requireNotNull(restoreTaskActionRouteState(state.savedValues()))
        val restoredActivation = requireNotNull(restored.active)
        assertTrue(
            restored.isArmed(
                restoredActivation,
                TaskExternalResultKind.DHIZUKU_PERMISSION,
            ),
        )
        assertSame(
            restoredActivation,
            restored.claimReplayable(
                restoredActivation,
                TaskExternalResultKind.DHIZUKU_PERMISSION,
            ),
        )

        state.releaseClaim(activation, TaskExternalResultKind.DHIZUKU_PERMISSION)
        assertSame(
            activation,
            state.claimReplayable(activation, TaskExternalResultKind.DHIZUKU_PERMISSION),
        )
    }

    @Test
    fun `external arm lookup requires exact activation and kind`() {
        val state = TaskActionRouteState()
        val activation = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))

        assertTrue(state.arm(activation, TaskExternalResultKind.DHIZUKU_PERMISSION))
        assertTrue(state.isArmed(activation, TaskExternalResultKind.DHIZUKU_PERMISSION))
        assertTrue(!state.isArmed(activation, TaskExternalResultKind.SHIZUKU_PERMISSION))

        val replacement = state.activate(TaskUiRoute.AuthorizePrivilege(OTHER_TASK_ID))
        assertTrue(!state.isArmed(activation, TaskExternalResultKind.DHIZUKU_PERMISSION))
        assertTrue(!state.isArmed(replacement, TaskExternalResultKind.DHIZUKU_PERMISSION))
    }

    @Test
    fun `stale external result cannot claim replacement armed for the same kind`() {
        val state = TaskActionRouteState()
        val first = state.activate(TaskUiRoute.PickRestoreSource(TASK_ID, "example.app"))
        assertTrue(state.arm(first, TaskExternalResultKind.RESTORE_SOURCE))

        val replacement = state.activate(TaskUiRoute.PickRestoreSource(OTHER_TASK_ID, "other.app"))
        assertTrue(state.arm(replacement, TaskExternalResultKind.RESTORE_SOURCE))

        assertNull(state.claim(first, TaskExternalResultKind.RESTORE_SOURCE))
        assertSame(
            replacement,
            state.claim(replacement, TaskExternalResultKind.RESTORE_SOURCE),
        )
    }

    @Test
    fun `saved route restores exact generation armed result and monotonic replacement`() {
        val state = TaskActionRouteState()
        val activation = state.activate(TaskUiRoute.PickRestoreSource(TASK_ID, "example.app"))
        assertTrue(state.arm(activation, TaskExternalResultKind.RESTORE_SOURCE))

        val restored = requireNotNull(restoreTaskActionRouteState(state.savedValues()))
        val restoredActivation = requireNotNull(restored.active)

        assertEquals(activation, restoredActivation)
        assertSame(
            restoredActivation,
            restored.claim(restoredActivation, TaskExternalResultKind.RESTORE_SOURCE),
        )
        assertTrue(
            restored.activate(TaskUiRoute.AuthorizePrivilege(OTHER_TASK_ID)).generation >
                    activation.generation,
        )
    }

    @Test
    fun `action submission ownership is released when saved state is restored`() {
        val state = TaskActionRouteState()
        val activation = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))
        assertTrue(state.arm(activation, TaskExternalResultKind.ACTION_SUBMISSION))

        val restored = requireNotNull(restoreTaskActionRouteState(state.savedValues()))
        val restoredActivation = requireNotNull(restored.active)

        assertEquals(activation, restoredActivation)
        assertTrue(
            restored.arm(restoredActivation, TaskExternalResultKind.ACTION_SUBMISSION),
        )
    }

    @Test
    fun `process local permission ownership is released across process death`() {
        listOf(
            TaskExternalResultKind.SHIZUKU_PERMISSION,
            TaskExternalResultKind.DHIZUKU_PERMISSION,
        ).forEach { kind ->
            val state = TaskActionRouteState()
            val activation = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))
            assertTrue(state.arm(activation, kind))
            val valuesFromPreviousProcess = state.savedValues().toMutableList().apply {
                this[lastIndex] = "previous-process"
            }

            val restored = requireNotNull(restoreTaskActionRouteState(valuesFromPreviousProcess))
            val restoredActivation = requireNotNull(restored.active)

            assertEquals(activation, restoredActivation)
            assertTrue(restored.arm(restoredActivation, kind))
        }
    }

    @Test
    fun `every task action route survives saved state round trip`() {
        val outputIds = listOf(OUTPUT_ID, OTHER_OUTPUT_ID)
        val routes = listOf(
            TaskUiRoute.AuthenticateArchive(TASK_ID, "example.app", DataTaskKind.ARCHIVE_RESTORE),
            TaskUiRoute.PickRestoreSource(TASK_ID, "example.app"),
            TaskUiRoute.ReviewInterruptedRestore(
                TASK_ID,
                RestoreMutationBreadcrumb("example.app", "Example", 123_456L),
            ),
            TaskUiRoute.AuthorizePrivilege(TASK_ID),
            TaskUiRoute.ConfirmSweepRetry(
                TASK_ID,
                targetOrdinal = 7,
                packageName = "example.app",
                operation = PrivilegeSweepOperation.REINSTALL,
            ),
            TaskUiRoute.SharePreparedOutputs(TASK_ID, outputIds),
            TaskUiRoute.OpenNotificationSettings(TASK_ID, "exact-channel"),
        )

        routes.forEach { route ->
            val state = TaskActionRouteState()
            val activation = state.activate(route)

            assertEquals(activation, restoreTaskActionRouteState(state.savedValues())?.active)
        }
    }

    @Test
    fun `saved state rejects malformed ownership and route values`() {
        val state = TaskActionRouteState()
        val activation = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))
        assertTrue(state.arm(activation, TaskExternalResultKind.PRIVILEGE_MANAGER))
        val values = state.savedValues()

        assertNull(
            restoreTaskActionRouteState(
                values.toMutableList().apply { this[0] = "unknown" })
        )
        assertNull(restoreTaskActionRouteState(values.toMutableList().apply { this[2] = "0" }))
        assertNull(restoreTaskActionRouteState(values.toMutableList().apply { this[3] = "2" }))
        assertNull(
            restoreTaskActionRouteState(
                values.toMutableList().apply { this[5] = "unknown" })
        )
        assertNull(restoreTaskActionRouteState(values.dropLast(1)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stale composable action callback cannot submit for its replacement`() = runTest {
        val state = TaskActionRouteState()
        val stale = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))
        val replacement = state.activate(TaskUiRoute.AuthorizePrivilege(OTHER_TASK_ID))
        var submissions = 0

        launchTaskAction(state, stale) {
            submissions += 1
            TaskActionDispatch.Applied
        }
        runCurrent()

        assertEquals(0, submissions)
        assertSame(replacement, state.active)
    }

    @Test
    fun `routed completion replaces instead of stacking active route`() {
        val state = TaskActionRouteState()
        val first = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))
        val destination = TaskUiRoute.OpenNotificationSettings(TASK_ID, "exact-channel")

        state.complete(first, TaskActionDispatch.Route(destination))

        val active = requireNotNull(state.active)
        assertEquals(destination, active.route)
        assertTrue(active.generation > first.generation)
    }

    @Test
    fun `dismiss and settled completion clear only the current generation`() {
        val state = TaskActionRouteState()
        val first = state.activate(TaskUiRoute.AuthorizePrivilege(TASK_ID))
        val second = state.activate(TaskUiRoute.AuthorizePrivilege(OTHER_TASK_ID))

        state.dismiss(first)
        assertSame(second, state.active)
        state.complete(
            second, TaskActionDispatch.Rejected(
                com.valhalla.thor.domain.repository.TaskActionRejection.STALE_PROJECTION,
            )
        )
        assertNull(state.active)
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OTHER_TASK_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val OUTPUT_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val OTHER_OUTPUT_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    }
}
