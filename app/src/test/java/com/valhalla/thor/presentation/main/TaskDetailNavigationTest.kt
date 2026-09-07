// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import androidx.navigation3.runtime.NavKey
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskActionRejection
import com.valhalla.thor.presentation.navigation.ThorRoute
import com.valhalla.thor.presentation.queue.TaskDetailActionResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDetailNavigationTest {

    @Test
    fun `successful acknowledgement pops only the displayed task detail`() {
        val route = ThorRoute.TaskDetail(TASK_ID.toString())
        val backStack = mutableListOf<NavKey>(ThorRoute.Home, route)
        val dismissed = mutableListOf<UUID>()
        val routed = mutableListOf<com.valhalla.thor.domain.repository.TaskUiRoute>()

        handleTaskDetailActionResult(
            backStack = backStack,
            route = route,
            result = TaskDetailActionResult(TaskAction.ACKNOWLEDGE, TaskActionDispatch.Applied),
            onDetailDismissed = { dismissed += it },
            onRoute = { routed += it },
        )

        assertEquals(listOf<NavKey>(ThorRoute.Home), backStack)
        assertEquals(listOf(TASK_ID), dismissed)
        assertTrue(routed.isEmpty())
    }

    @Test
    fun `successful acknowledgement removes exact detail below a newer detail`() {
        val route = ThorRoute.TaskDetail(TASK_ID.toString())
        val newerRoute = ThorRoute.TaskDetail(OTHER_TASK_ID.toString())
        val backStack = mutableListOf<NavKey>(ThorRoute.Home, route, newerRoute)
        val dismissed = mutableListOf<UUID>()

        handleTaskDetailActionResult(
            backStack = backStack,
            route = route,
            result = TaskDetailActionResult(TaskAction.ACKNOWLEDGE, TaskActionDispatch.Applied),
            onDetailDismissed = { dismissed += it },
            onRoute = {},
        )

        assertEquals(listOf<NavKey>(ThorRoute.Home, newerRoute), backStack)
        assertEquals(listOf(TASK_ID), dismissed)
    }

    @Test
    fun `rejected acknowledgement keeps the displayed task detail`() {
        val route = ThorRoute.TaskDetail(TASK_ID.toString())
        val backStack = mutableListOf<NavKey>(ThorRoute.Home, route)
        val dismissed = mutableListOf<UUID>()

        handleTaskDetailActionResult(
            backStack = backStack,
            route = route,
            result = TaskDetailActionResult(
                TaskAction.ACKNOWLEDGE,
                TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION),
            ),
            onDetailDismissed = { dismissed += it },
            onRoute = {},
        )

        assertEquals(listOf<NavKey>(ThorRoute.Home, route), backStack)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun `route dispatch keeps task detail and forwards destination`() {
        val route = ThorRoute.TaskDetail(TASK_ID.toString())
        val backStack = mutableListOf<NavKey>(ThorRoute.Home, route)
        val destination = com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID)
        val routed = mutableListOf<com.valhalla.thor.domain.repository.TaskUiRoute>()

        handleTaskDetailActionResult(
            backStack = backStack,
            route = route,
            result = TaskDetailActionResult(
                TaskAction.AUTHORIZE_PRIVILEGE,
                TaskActionDispatch.Route(destination),
            ),
            onDetailDismissed = {},
            onRoute = { routed += it },
        )

        assertEquals(listOf<NavKey>(ThorRoute.Home, route), backStack)
        assertEquals(listOf(destination), routed)
    }

    @Test
    fun `opening an existing task brings one exact detail to the front`() {
        val route = ThorRoute.TaskDetail(TASK_ID.toString())
        val otherRoute = ThorRoute.TaskDetail(OTHER_TASK_ID.toString())
        val backStack = mutableListOf<NavKey>(ThorRoute.Home, route, otherRoute, route)

        bringTaskDetailToFront(backStack, TASK_ID)

        assertEquals(listOf<NavKey>(ThorRoute.Home, otherRoute, route), backStack)
        assertEquals(1, backStack.count { it == route })
    }

    @Test
    fun `top task queue lookup preserves lifecycle cancellation`() = runTest {
        var cancelled = false

        try {
            resolveTopTaskQueueKind(provisionalQueueKind = null) {
                throw CancellationException("owner paused")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    @Test
    fun `top task queue lookup treats repository failure as unavailable`() = runTest {
        assertEquals(
            null,
            resolveTopTaskQueueKind(provisionalQueueKind = null) {
                error("database unavailable")
            },
        )
        assertEquals(
            TaskQueueKind.DATA,
            resolveTopTaskQueueKind(provisionalQueueKind = TaskQueueKind.DATA) {
                error("provisional identity should win")
            },
        )
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OTHER_TASK_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
