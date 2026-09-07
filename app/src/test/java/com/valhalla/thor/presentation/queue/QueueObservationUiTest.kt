// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class QueueObservationUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unavailableObservationShowsExplanationNotEmptyOrSuccessfulSections() {
        rule.setContent {
            MaterialTheme {
                QueueContent(
                    state = QueueUiState(isLoading = false, observationUnavailable = true),
                    onBack = {}, onTaskSelected = {}, onAction = { _, _ -> },
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        rule.onNodeWithText(context.getString(R.string.task_reason_observer_failure)).assertIsDisplayed()
        listOf(
            R.string.task_queue_empty, R.string.task_queue_empty_running,
            R.string.task_queue_empty_queued, R.string.task_queue_empty_recent,
        ).forEach { rule.onNodeWithText(context.getString(it)).assertDoesNotExist() }
    }

    @Test fun guardiansHeaderKeepsTaskQueueDiscoverableWhileLoading() {
        rule.setContent {
            MaterialTheme {
                QueueContent(QueueUiState(), onBack = {}, onTaskSelected = {}, onAction = { _, _ -> })
            }
        }
        rule.onNodeWithText("Guardians").assertIsDisplayed()
        rule.onNodeWithText("Task queue").assertIsDisplayed()
    }

    @Test fun providerRosterPairsEachGuardianWithItsTechnicalProvider() {
        rule.setContent {
            MaterialTheme {
                QueueContent(QueueUiState(isLoading = false),
                    onBack = {}, onTaskSelected = {}, onAction = { _, _ -> })
            }
        }
        rule.onNodeWithText("Privilege providers").assertIsDisplayed()
        rule.onNode(hasText("Groot") and hasText("Root")).assertIsDisplayed()
        rule.onNode(hasText("Rocket") and hasText("Shizuku")).assertIsDisplayed()
        rule.onNode(hasText("Star-Lord") and hasText("Dhizuku")).assertIsDisplayed()
    }

    @Test fun providerRosterFitsNarrowRtlSurfaceAtLargeText() {
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.5f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.width(280.dp).testTag("roster-viewport")) { GuardianRoster() }
                }
            }
        }
        val viewport = rule.onNodeWithTag("roster-viewport").getUnclippedBoundsInRoot()
        val portraits = listOf("Groot" to "Root", "Rocket" to "Shizuku", "Star-Lord" to "Dhizuku")
            .map { (name, provider) ->
                rule.onNode(hasText(name) and hasText(provider)).assertIsDisplayed().getUnclippedBoundsInRoot()
            }
        portraits.forEach { bounds ->
            assertTrue("$bounds inside $viewport", bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        portraits.zipWithNext().forEach { (right, left) ->
            assertTrue("RTL portraits must not overlap: $left / $right", left.right <= right.left)
        }
    }

    @Test fun recentDataTaskHidesCloseButStillOpensDetails() {
        checkRecentTaskWithoutClose(TaskQueueKind.DATA)
    }

    @Test fun recentPrivilegeTaskHidesCloseButStillOpensDetails() {
        checkRecentTaskWithoutClose(TaskQueueKind.PRIVILEGE)
    }

    @Test fun actionableQueueButtonStillDispatchesWithoutOpeningDetails() {
        val task = task(TaskQueueKind.DATA, TaskLifecyclePhase.WAITING_FOR_SOURCE,
            setOf(TaskAction.PROVIDE_SOURCE))
        val selected = mutableListOf<UUID>()
        val performed = mutableListOf<Pair<UUID, TaskAction>>()
        rule.setContent {
            MaterialTheme {
                QueueContent(
                    state = QueueUiState(isLoading = false,
                        queued = QueueLaneSectionUiState(data = listOf(task))),
                    onBack = {}, onTaskSelected = selected::add,
                    onAction = { id, action -> performed.add(id to action) },
                )
            }
        }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(queueRowTag(task.taskId)))
        rule.onNodeWithTag(queueActionTag(task.taskId, TaskAction.PROVIDE_SOURCE))
            .performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(listOf(task.taskId to TaskAction.PROVIDE_SOURCE), performed)
            assertTrue(selected.isEmpty())
        }
    }

    private fun checkRecentTaskWithoutClose(queueKind: TaskQueueKind) {
        val task = task(queueKind, TaskLifecyclePhase.SUCCEEDED, setOf(TaskAction.ACKNOWLEDGE))
        val recent = when (queueKind) {
            TaskQueueKind.DATA -> QueueLaneSectionUiState(data = listOf(task))
            TaskQueueKind.PRIVILEGE -> QueueLaneSectionUiState(privilege = listOf(task))
        }
        val selected = mutableListOf<UUID>()
        val performed = mutableListOf<Pair<UUID, TaskAction>>()
        rule.setContent {
            MaterialTheme {
                QueueContent(
                    state = QueueUiState(isLoading = false, recent = recent),
                    onBack = {}, onTaskSelected = selected::add,
                    onAction = { id, action -> performed.add(id to action) },
                )
            }
        }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(queueRowTag(task.taskId)))
        rule.onNodeWithTag(queueRowTag(task.taskId)).assertIsDisplayed()
        rule.onNodeWithTag(queueActionTag(task.taskId, TaskAction.ACKNOWLEDGE)).assertDoesNotExist()
        val context = ApplicationProvider.getApplicationContext<Application>()
        rule.onNodeWithText(context.getString(R.string.task_queue_close)).assertDoesNotExist()
        rule.onNodeWithTag(queueRowTag(task.taskId)).performClick()
        rule.runOnIdle {
            assertEquals(listOf(task.taskId), selected)
            assertTrue(performed.isEmpty())
        }
    }

    private fun task(
        queueKind: TaskQueueKind,
        phase: TaskLifecyclePhase,
        actions: Set<TaskAction>,
    ) = QueuedTaskSummary(
        taskId = UUID(0, 42), queueKind = queueKind,
        operationId = if (queueKind == TaskQueueKind.DATA) "ARCHIVE_RESTORE" else "REINSTALL",
        titleArguments = listOf("Example"), sequence = 1, phase = phase,
        progress = TaskProgress(1, 1, null), activeItemLabel = null,
        actionRequirement = null, actions = actions,
        terminalAtEpochMs = if (phase == TaskLifecyclePhase.SUCCEEDED) 1000 else null,
        retainUntilEpochMs = if (phase == TaskLifecyclePhase.SUCCEEDED) 2000 else null,
        rootLaneDegraded = false,
    )
}
