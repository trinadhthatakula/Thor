// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.QueuedTaskDetail
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskActionPolicy
import com.valhalla.thor.domain.model.TaskActionRequirement
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskLogLine
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.presentation.widgets.TermLoggerStatus
import com.valhalla.thor.presentation.widgets.termLoggerLineTag
import com.valhalla.thor.util.ServiceQueueOperation
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDetailScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun activeLoggerShowsBackgroundAndOnlyAdvertisedExactTaskAction() {
        val actions = mutableListOf<TaskAction>()
        var backgroundCount = 0
        setContent(
            state(TaskLifecyclePhase.RUNNING),
            onBackground = { backgroundCount++ },
            onAction = actions::add,
        )

        rule.onNodeWithTag(TASK_DETAIL_BACKGROUND_TAG)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(taskDetailActionTag(TaskAction.CANCEL))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(taskDetailActionTag(TaskAction.SHARE)).assertDoesNotExist()

        rule.runOnIdle {
            assertEquals(1, backgroundCount)
            assertEquals(listOf(TaskAction.CANCEL), actions)
        }
    }

    @Test
    fun stoppingKeepsBackgroundAndShowsDisabledStoppingInsteadOfCancel() {
        setContent(state(TaskLifecyclePhase.STOPPING))

        rule.onNodeWithTag(TASK_DETAIL_BACKGROUND_TAG).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(TASK_DETAIL_STOPPING_TAG)
            .assertHeightIsAtLeast(48.dp)
            .assertIsNotEnabled()
            .assertTextContains(rule.activity.getString(R.string.task_state_stopping))
        rule.onNodeWithTag(taskDetailActionTag(TaskAction.CANCEL)).assertDoesNotExist()
    }

    @Test
    fun terminalCloseAcknowledgesWhileExpiredOutputCannotBeShared() {
        val actions = mutableListOf<TaskAction>()
        setContent(state(TaskLifecyclePhase.EXPIRED), onAction = actions::add)

        rule.onNodeWithTag(termLoggerLineTag(2), useUnmergedTree = true)
            .assertTextContains(
                rule.activity.getString(R.string.task_reason_expired),
                substring = true,
            )
        rule.onNodeWithTag(taskDetailActionTag(TaskAction.SHARE)).assertDoesNotExist()
        rule.onNodeWithTag(TASK_DETAIL_BACKGROUND_TAG).assertDoesNotExist()
        rule.onNodeWithTag(taskDetailActionTag(TaskAction.ACKNOWLEDGE))
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains(rule.activity.getString(R.string.task_queue_close))
            .performClick()

        rule.runOnIdle { assertEquals(listOf(TaskAction.ACKNOWLEDGE), actions) }
    }

    @Test
    fun actionRequiredLoggerRendersOnlyItsPolicyAction() {
        val requirement = TaskActionRequirement.ArchiveAuthentication(
            packageName = "app.archive",
            kind = DataTaskKind.ARCHIVE_BACKUP,
        )
        setContent(state(TaskLifecyclePhase.WAITING_FOR_AUTH, requirement))

        rule.onNodeWithTag(taskDetailActionTag(TaskAction.AUTHENTICATE_ARCHIVE))
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains(rule.activity.getString(R.string.task_action_authenticate_archive))
        rule.onNodeWithTag(taskDetailActionTag(TaskAction.CANCEL)).assertDoesNotExist()
        rule.onNodeWithTag(taskDetailActionTag(TaskAction.ACKNOWLEDGE)).assertDoesNotExist()
    }

    @Test
    fun everySuppliedTaskActionHasAnExplicitPresentation() {
        val allActions = TaskAction.entries.toSet()
        setContent(state(TaskLifecyclePhase.RUNNING, actions = allActions))

        val labels = mapOf(
            TaskAction.CANCEL to R.string.task_queue_cancel,
            TaskAction.AUTHENTICATE_ARCHIVE to R.string.task_action_authenticate_archive,
            TaskAction.PROVIDE_SOURCE to R.string.task_action_provide_source,
            TaskAction.REVIEW_RESTORE to R.string.task_action_review_restore,
            TaskAction.AUTHORIZE_PRIVILEGE to R.string.task_action_authorize_privilege,
            TaskAction.AUTHORIZE_SWEEP_RETRY to R.string.task_action_authorize_retry,
            TaskAction.RETRY to R.string.task_action_retry,
            TaskAction.RESUME to R.string.task_action_resume,
            TaskAction.SHARE to R.string.task_action_share,
            TaskAction.OPEN_NOTIFICATION_SETTINGS to R.string.task_action_open_notification_settings,
            TaskAction.ACKNOWLEDGE to R.string.task_queue_close,
        )
        labels.forEach { (action, label) ->
            rule.onNodeWithTag(taskDetailActionTag(action))
                .assertTextContains(rule.activity.getString(label))
        }
    }

    @Test
    fun readyShareRemainsVisibleUntilDurableStateChanges() {
        val outputId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val requirement = TaskActionRequirement.PreparedShare(listOf(outputId))
        val actions = mutableListOf<TaskAction>()
        setContent(
            state(TaskLifecyclePhase.READY, requirement),
            onAction = actions::add,
        )

        val share = rule.onNodeWithTag(taskDetailActionTag(TaskAction.SHARE))
        share.performClick()
        share.assertExists()
        rule.runOnIdle { assertEquals(listOf(TaskAction.SHARE), actions) }
    }

    @Test
    fun durableLogLinesAreLocalizedAndRenderedInOrder() {
        setContent(
            state(
                phase = TaskLifecyclePhase.RUNNING,
                lines = listOf(
                    TaskLogLine(2, "TASK_ITEM_RUNNING", listOf("app.second")),
                    TaskLogLine(1, "TASK_ITEM_SUCCEEDED", listOf("app.first")),
                ),
            ),
        )

        rule.onNodeWithTag(termLoggerLineTag(2), useUnmergedTree = true)
            .assertTextContains(
                rule.activity.getString(R.string.task_log_item_succeeded, "app.first"),
                substring = true,
            )
        rule.onNodeWithTag(termLoggerLineTag(3), useUnmergedTree = true)
            .assertTextContains(
                rule.activity.getString(R.string.task_log_item_running, "app.second"),
                substring = true,
            )
        rule.onNodeWithText("TASK_ITEM_SUCCEEDED").assertDoesNotExist()
        rule.onNodeWithText("TASK_ITEM_RUNNING").assertDoesNotExist()
    }

    @Test
    fun recognizedProgressStageIsLocalized() {
        setContent(
            state(
                phase = TaskLifecyclePhase.RUNNING,
                stageLabel = DataTaskStage.PUBLISHING.name,
            ),
        )

        rule.onNodeWithTag(termLoggerLineTag(2), useUnmergedTree = true)
            .assertTextContains(
                rule.activity.getString(R.string.task_log_stage_publishing),
                substring = true,
            )
        rule.onNodeWithText(DataTaskStage.PUBLISHING.name).assertDoesNotExist()
    }

    @Test
    fun unknownProgressStageIsNotRenderedVerbatim() {
        setContent(
            state(
                phase = TaskLifecyclePhase.RUNNING,
                stageLabel = "INTERNAL_SECRET_STAGE",
            ),
        )

        rule.onNodeWithText("INTERNAL_SECRET_STAGE").assertDoesNotExist()
    }

    @Test
    fun unknownLogCodeIsNotRenderedVerbatim() {
        setContent(
            state(
                phase = TaskLifecyclePhase.RUNNING,
                lines = listOf(TaskLogLine(1, "UNRECOGNIZED_DIAGNOSTIC", listOf("secret"))),
            ),
        )

        rule.onNodeWithText("UNRECOGNIZED_DIAGNOSTIC").assertDoesNotExist()
        rule.onNodeWithText("secret").assertDoesNotExist()
    }

    @Test
    fun provisionalStartingExportMarksLoggerVisibleOnFirstDraw() {
        val markers = mutableListOf<ServiceQueueOperation>()
        setContent(
            TaskDetailUiState(
                taskId = TASK_ID,
                phase = TaskLifecyclePhase.STARTING,
                provisionalIdentity = ProvisionalTaskIdentity(
                    TaskQueueKind.DATA,
                    DataTaskKind.APP_EXPORT.name,
                ),
            ),
            onLoggerVisible = markers::add,
        )

        rule.waitForIdle()

        rule.runOnIdle { assertEquals(listOf(ServiceQueueOperation.EXPORT), markers) }
    }

    @Test
    fun activeExportMarksLoggerVisibleOnceAfterDraw() {
        val markers = mutableListOf<ServiceQueueOperation>()
        setContent(
            state(TaskLifecyclePhase.RUNNING),
            onLoggerVisible = markers::add,
        )

        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.window.decorView.invalidate() }
        rule.waitForIdle()

        rule.runOnIdle { assertEquals(listOf(ServiceQueueOperation.EXPORT), markers) }
    }

    @Test
    fun activePrivilegeLoggerUsesPrivilegeSweepMarker() {
        val markers = mutableListOf<ServiceQueueOperation>()
        setContent(
            state(
                phase = TaskLifecyclePhase.RUNNING,
                queueKind = TaskQueueKind.PRIVILEGE,
                operationId = PrivilegeSweepOperation.FREEZE.name,
            ),
            onLoggerVisible = markers::add,
        )

        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals(listOf(ServiceQueueOperation.PRIVILEGE_SWEEP), markers)
        }
    }

    @Test
    fun markerSelectionRejectsInactiveAndUnsupportedTasks() {
        assertEquals(
            null,
            state(TaskLifecyclePhase.SUCCEEDED).loggerLatencyOperation(),
        )
        assertEquals(
            null,
            state(
                phase = TaskLifecyclePhase.RUNNING,
                operationId = DataTaskKind.ARCHIVE_BACKUP.name,
            ).loggerLatencyOperation(),
        )
        assertEquals(
            null,
            state(
                phase = TaskLifecyclePhase.RUNNING,
                operationId = DataTaskKind.SHARE_PREPARE.name,
            ).loggerLatencyOperation(),
        )
        assertEquals(
            null,
            TaskDetailUiState(
                taskId = TASK_ID,
                phase = TaskLifecyclePhase.OBSERVER_FAILURE,
            ).loggerLatencyOperation(),
        )
    }

    @Test
    fun onlySuccessfulTerminalStateUsesSuccessPresentation() {
        assertEquals(TermLoggerStatus.SUCCESS, state(TaskLifecyclePhase.SUCCEEDED).loggerStatus())
        assertEquals(TermLoggerStatus.NEUTRAL, state(TaskLifecyclePhase.FAILED).loggerStatus())
        assertEquals(
            TermLoggerStatus.NEUTRAL,
            state(
                TaskLifecyclePhase.WAITING_FOR_AUTH,
                TaskActionRequirement.ArchiveAuthentication(
                    "app.archive",
                    DataTaskKind.ARCHIVE_BACKUP,
                ),
            ).loggerStatus(),
        )
        assertEquals(
            TermLoggerStatus.NEUTRAL,
            TaskDetailUiState(
                taskId = TASK_ID,
                phase = TaskLifecyclePhase.OBSERVER_FAILURE,
            ).loggerStatus(),
        )
    }

    @Test
    fun loggerAndScrimDoNotExposeSyntheticClickActions() {
        setContent(
            state(
                TaskLifecyclePhase.WAITING_FOR_AUTH,
                TaskActionRequirement.ArchiveAuthentication(
                    "app.archive",
                    DataTaskKind.ARCHIVE_BACKUP,
                ),
            ),
        )
        val hasNoClickAction = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

        rule.onNodeWithTag(TASK_DETAIL_SCRIM_TAG).assert(hasNoClickAction)
        rule.onNodeWithTag(TASK_DETAIL_LOGGER_TAG).assert(hasNoClickAction)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.cd_selected))
            .assertDoesNotExist()
    }

    @Test
    fun systemBackDismissesUiWithoutDispatchingCancellation() {
        val actions = mutableListOf<TaskAction>()
        var backgroundCount = 0
        setContent(
            state(TaskLifecyclePhase.RUNNING),
            onBackground = { backgroundCount++ },
            onAction = actions::add,
        )

        pressBack()

        rule.runOnIdle {
            assertEquals(1, backgroundCount)
            assertEquals(emptyList<TaskAction>(), actions)
        }
    }

    @Test
    fun tappingOutsideLoggerDismissesUiWithoutDispatchingCancellation() {
        val actions = mutableListOf<TaskAction>()
        var backgroundCount = 0
        setContent(
            state(TaskLifecyclePhase.RUNNING),
            onBackground = { backgroundCount++ },
            onAction = actions::add,
        )

        rule.onNodeWithTag(TASK_DETAIL_SCRIM_TAG).performTouchInput {
            click(Offset(center.x, 1f))
        }

        rule.runOnIdle {
            assertEquals(1, backgroundCount)
            assertEquals(emptyList<TaskAction>(), actions)
        }
    }

    private fun setContent(
        state: TaskDetailUiState,
        onBackground: () -> Unit = {},
        onAction: (TaskAction) -> Unit = {},
        onLoggerVisible: (ServiceQueueOperation) -> Unit = {},
    ) {
        rule.setContent {
            MaterialTheme {
                TaskDetailContent(
                    state = state,
                    onBackground = onBackground,
                    onAction = onAction,
                    onLoggerVisible = onLoggerVisible,
                )
            }
        }
    }

    private fun state(
        phase: TaskLifecyclePhase,
        requirement: TaskActionRequirement? = null,
        actions: Set<TaskAction> = TaskActionPolicy.actionsFor(phase, requirement),
        lines: List<TaskLogLine> = emptyList(),
        queueKind: TaskQueueKind = TaskQueueKind.DATA,
        operationId: String = DataTaskKind.APP_EXPORT.name,
        stageLabel: String? = null,
    ): TaskDetailUiState {
        val summary = QueuedTaskSummary(
            taskId = TASK_ID,
            queueKind = queueKind,
            operationId = operationId,
            titleArguments = listOf("Example"),
            sequence = 1,
            phase = phase,
            progress = TaskProgress(1, 2, stageLabel),
            activeItemLabel = "Example",
            actionRequirement = requirement,
            actions = actions,
            terminalAtEpochMs = null,
            retainUntilEpochMs = null,
            rootLaneDegraded = false,
        )
        return TaskDetailUiState(
            taskId = TASK_ID,
            phase = phase,
            detail = QueuedTaskDetail(
                summary = summary,
                lines = lines,
                resultCode = null,
                warningCodes = emptyList(),
            ),
        )
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
