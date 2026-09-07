// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.presentation.widgets.TermLoggerContent
import com.valhalla.thor.presentation.widgets.termLoggerLineTag
import com.valhalla.thor.util.UiText
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w400dp-h600dp-mdpi")
class TaskDetailFooterTest {
    @get:Rule val rule = createComposeRule()

    // These exercise the real Dialog route and action mapping, not a claimed small viewport.
    @Test fun longReadyShareKeepsBackgroundAndShareTouchable() {
        checkRouteFooter(TaskLifecyclePhase.READY, TaskAction.SHARE)
    }

    @Test fun longRunningTaskKeepsBackgroundAndCancelTouchable() {
        checkRouteFooter(TaskLifecyclePhase.RUNNING, TaskAction.CANCEL)
    }

    @Test fun measuredSmallReadyLoggerKeepsFooterInsideViewport() {
        checkBoundedLogger(TaskLifecyclePhase.READY, TaskAction.SHARE)
    }

    @Test fun measuredSmallActiveLoggerKeepsFooterInsideViewport() {
        checkBoundedLogger(TaskLifecyclePhase.RUNNING, TaskAction.CANCEL)
    }

    @Test fun productionRouteSeparatesFooterControlsFromLogsAndEachOther() {
        val state = detailState(TaskLifecyclePhase.READY)
        rule.setContent {
            MaterialTheme {
                TaskDetailContent(state, onBackground = {}, onAction = {})
            }
        }
        val lastLine = rule.onNodeWithTag(termLoggerLineTag(66)).assertIsDisplayed().getUnclippedBoundsInRoot()
        val background = rule.onNodeWithTag(TASK_DETAIL_BACKGROUND_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        val share = rule.onNodeWithTag(taskDetailActionTag(TaskAction.SHARE)).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("At least 16dp between logs and actions: $lastLine -> $background", background.top - lastLine.bottom >= 16.dp)
        assertTrue("At least 8dp between actions: $background -> $share", share.top - background.bottom >= 8.dp)
    }

    @Test fun typedPendingFixtureUsesOneQuantityLine() {
        val state = detailState(TaskLifecyclePhase.RUNNING).let { initial ->
            initial.copy(detail = requireNotNull(initial.detail).copy(
                lines = List(63) { TaskLogLine(it.toLong(), "TASK_ITEM_PENDING", listOf("app.number$it")) },
            ))
        }
        rule.setContent {
            MaterialTheme { TaskDetailContent(state, onBackground = {}, onAction = {}) }
        }
        rule.onNodeWithText("> 63 tasks accepted and queued").assertIsDisplayed()
    }

    private fun detailState(phase: TaskLifecyclePhase): TaskDetailUiState {
        val id = UUID(0, 42)
        val requirement = when (phase) {
            TaskLifecyclePhase.READY -> TaskActionRequirement.PreparedShare(listOf(UUID(0, 43)))
            TaskLifecyclePhase.START_BLOCKED_NOTIFICATION -> TaskActionRequirement.NotificationSettings("test-channel")
            else -> null
        }
        val summary = QueuedTaskSummary(
            taskId = id, queueKind = TaskQueueKind.DATA, operationId = DataTaskKind.SHARE_PREPARE.name,
            titleArguments = listOf("Selection"), sequence = 1, phase = phase,
            progress = TaskProgress(64, 64, null), activeItemLabel = null,
            actionRequirement = requirement, actions = TaskActionPolicy.actionsFor(phase, requirement),
            terminalAtEpochMs = null, retainUntilEpochMs = null, rootLaneDegraded = false,
        )
        return TaskDetailUiState(id, phase = phase, detail = QueuedTaskDetail(
            summary = summary,
            lines = List(64) { TaskLogLine(it.toLong(), "TASK_ITEM_SUCCEEDED", listOf("app.number$it")) },
            resultCode = null, warningCodes = emptyList(),
        ))
    }

    private fun checkRouteFooter(phase: TaskLifecyclePhase, action: TaskAction) {
        val state = detailState(phase)
        val actions = mutableListOf<TaskAction>()
        var backgrounds = 0
        rule.setContent {
            MaterialTheme {
                TaskDetailContent(state, onBackground = { backgrounds++ }, onAction = actions::add)
            }
        }
        rule.onNodeWithTag(TASK_DETAIL_BACKGROUND_TAG).assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(taskDetailActionTag(action)).assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertEquals(1, backgrounds)
            assertEquals(listOf(action), actions)
        }
    }

    @Test fun shorterLargeTextReadyLoggerKeepsTwoControlsReachable() {
        checkBoundedLogger(TaskLifecyclePhase.READY, TaskAction.SHARE, 400.dp, 1.3f)
    }

    @Test fun shorterLargeTextBlockedLoggerKeepsThreeControlsReachable() {
        checkBoundedLogger(TaskLifecyclePhase.START_BLOCKED_NOTIFICATION, TaskAction.RETRY, 400.dp, 1.3f)
    }

    @Test fun shorterStoppingLoggerKeepsDisabledControlAndBackground() {
        checkBoundedLogger(TaskLifecyclePhase.STOPPING, null, 400.dp, 1.3f)
    }

    private fun checkBoundedLogger(
        phase: TaskLifecyclePhase,
        action: TaskAction?,
        height: Dp = 480.dp,
        fontScale: Float = 1f,
    ) {
        val state = detailState(phase)
        val actions = mutableListOf<TaskAction>()
        var backgrounds = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    TermLoggerContent(
                        title = UiText.StringResource(R.string.freezer),
                        logs = state.loggerLines(),
                        status = state.loggerStatus(),
                        modifier = Modifier.size(360.dp, height).testTag("bounded-logger"),
                    ) {
                        TaskDetailFooter(state, onBackground = { backgrounds++ }, onAction = actions::add)
                    }
                }
            }
        }
        assertMeasuredFooter(state, height)
        rule.onNodeWithTag(TASK_DETAIL_BACKGROUND_TAG).performClick()
        if (action != null) rule.onNodeWithTag(taskDetailActionTag(action)).performClick()
        if (phase == TaskLifecyclePhase.STOPPING) rule.onNodeWithTag(TASK_DETAIL_STOPPING_TAG).assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, backgrounds)
            assertEquals(listOfNotNull(action), actions)
        }
    }

    @Test fun queuedMixedAndTerminalSnapshotsKeepLastLineAndFooterReachable() {
        val initial = detailState(TaskLifecyclePhase.QUEUED)
        val current = mutableStateOf(initial.copy(detail = initial.detail!!.copy(
            lines = listOf(TaskLogLine(0, "TASK_PENDING_COUNT", listOf("80"))),
        )))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                MaterialTheme {
                    TermLoggerContent(
                        title = UiText.StringResource(R.string.freezer), logs = current.value.loggerLines(),
                        status = current.value.loggerStatus(),
                        modifier = Modifier.size(360.dp, 400.dp).testTag("bounded-logger"),
                    ) { TaskDetailFooter(current.value, onBackground = {}, onAction = {}) }
                }
            }
        }
        assertMeasuredFooter(current.value, 400.dp)
        val running = detailState(TaskLifecyclePhase.RUNNING)
        rule.runOnIdle {
            current.value = running.copy(detail = running.detail!!.copy(lines =
                List(62) { TaskLogLine(it.toLong(), "TASK_ITEM_SUCCEEDED", listOf("app.number$it")) } +
                    TaskLogLine(62, "TASK_ITEM_RUNNING", listOf("app.number62")) +
                    TaskLogLine(63, "TASK_PENDING_COUNT", listOf("17")),
            ))
        }
        assertMeasuredFooter(current.value, 400.dp)
        // Same length: the running row becomes an outcome, while the pending summary changes.
        rule.runOnIdle {
            current.value = current.value.copy(detail = current.value.detail!!.copy(lines =
                List(63) { TaskLogLine(it.toLong(), "TASK_ITEM_SUCCEEDED", listOf("app.number$it")) } +
                    TaskLogLine(63, "TASK_PENDING_COUNT", listOf("16")),
            ))
        }
        assertMeasuredFooter(current.value, 400.dp)
        rule.runOnIdle { current.value = detailState(TaskLifecyclePhase.SUCCEEDED) }
        assertMeasuredFooter(current.value, 400.dp)
    }

    private fun assertMeasuredFooter(state: TaskDetailUiState, height: Dp) {
        val viewport = rule.onNodeWithTag("bounded-logger").assertIsDisplayed()
            .assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(height).getUnclippedBoundsInRoot()
        var previous = rule.onNodeWithTag(termLoggerLineTag(state.loggerLines().lastIndex))
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val tags = buildList {
            if (TaskAction.ACKNOWLEDGE !in state.actions) add(TASK_DETAIL_BACKGROUND_TAG)
            if (state.phase == TaskLifecyclePhase.STOPPING) add(TASK_DETAIL_STOPPING_TAG)
            TaskAction.entries.filter { it in state.actions }.forEach { add(taskDetailActionTag(it)) }
        }
        println("Measured ${state.phase} viewport=$viewport; final line=$previous")
        tags.forEachIndexed { index, tag ->
            val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .getUnclippedBoundsInRoot()
            println("Measured $tag: $bounds")
            assertTrue("$tag inside $viewport", bounds.left >= viewport.left && bounds.top >= viewport.top &&
                bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
            assertTrue("$tag has 16dp horizontal inset", bounds.left >= viewport.left + 16.dp && bounds.right <= viewport.right - 16.dp)
            assertTrue("$tag has spacing after $previous", bounds.top - previous.bottom >= if (index == 0) 16.dp else 8.dp)
            previous = bounds
        }
    }
}
