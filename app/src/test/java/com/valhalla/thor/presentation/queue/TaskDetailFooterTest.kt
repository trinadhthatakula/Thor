// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    private fun detailState(phase: TaskLifecyclePhase): TaskDetailUiState {
        val id = UUID(0, 42)
        val requirement = if (phase == TaskLifecyclePhase.READY)
            TaskActionRequirement.PreparedShare(listOf(UUID(0, 43))) else null
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

    private fun checkBoundedLogger(phase: TaskLifecyclePhase, action: TaskAction) {
        val state = detailState(phase)
        // The route tests above exercise the private production projection. Here the same 64
        // item-success resources feed the real logger directly; the footer is a layout fixture.
        val logs = state.lines.map {
            UiText.StringResource(R.string.task_log_item_succeeded, it.arguments.single())
        }
        assertEquals(64, logs.size)
        val actions = mutableListOf<TaskAction>()
        var backgrounds = 0
        rule.setContent {
            MaterialTheme {
                TermLoggerContent(
                    title = UiText.StringResource(R.string.freezer),
                    logs = logs,
                    status = state.loggerStatus(),
                    modifier = Modifier.size(360.dp, 480.dp).testTag("bounded-logger"),
                ) {
                    OutlinedButton(
                        onClick = { backgrounds++ },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("bounded-background"),
                    ) { Text("Background") }
                    Button(
                        onClick = { actions += action },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("bounded-action"),
                    ) { Text(action.name) }
                }
            }
        }
        val logger = rule.onNodeWithTag("bounded-logger").assertIsDisplayed()
            .assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(480.dp)
        val viewport = logger.getUnclippedBoundsInRoot()
        println("Measured $phase logger viewport: $viewport; projected item lines=${logs.size}")
        // Auto-scroll reaches the actual final line without moving either footer action away.
        rule.onNodeWithTag(termLoggerLineTag(63)).assertIsDisplayed()
        listOf("bounded-background", "bounded-action").forEach { tag ->
            val footer = rule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = footer.getUnclippedBoundsInRoot()
            println("Measured $phase $tag: $bounds")
            assertTrue("$tag must be fully inside the measured logger viewport",
                bounds.left >= viewport.left && bounds.top >= viewport.top &&
                    bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
            footer.performClick()
        }
        rule.runOnIdle {
            assertEquals(1, backgrounds)
            assertEquals(listOf(action), actions)
        }
    }
}
