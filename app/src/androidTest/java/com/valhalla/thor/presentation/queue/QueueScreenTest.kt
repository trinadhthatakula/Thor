// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyStateShowsAllSectionsAndSectionSpecificMessages() {
        setContent(QueueUiState(isLoading = false))

        assertText(R.string.task_queue_empty)
        assertText(R.string.task_queue_section_running)
        assertText(R.string.task_queue_empty_running)
        assertText(R.string.task_queue_section_queued)
        assertText(R.string.task_queue_empty_queued)
        assertText(R.string.task_queue_section_recent)
        assertText(R.string.task_queue_empty_recent)
    }

    @Test
    fun sectionLabelsExposeHeadingSemantics() {
        setContent(QueueUiState(isLoading = false))
        val heading = SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)

        listOf(
            R.string.task_queue_section_running,
            R.string.task_queue_section_queued,
            R.string.task_queue_section_recent,
        ).forEach { label ->
            scrollToNode(hasText(rule.activity.getString(label))).assert(heading)
        }
    }

    @Test
    fun rowWithoutPackageLabelShowsSafeItemCountSummary() {
        val task = task(
            taskId = PRIVILEGE_TASK,
            queueKind = TaskQueueKind.PRIVILEGE,
            operationId = "FREEZE",
            phase = TaskLifecyclePhase.QUEUED,
            titleArguments = emptyList(),
            activeItemLabel = null,
        )
        setContent(
            QueueUiState(
                isLoading = false,
                queued = QueueLaneSectionUiState(privilege = listOf(task)),
            ),
        )

        scrollToNode(hasTestTag(queueRowTag(PRIVILEGE_TASK))).assertTextContains(
            rule.activity.resources.getQuantityString(R.plurals.profile_app_count, 2, 2),
        )
    }

    @Test
    fun independentDataAndPrivilegeTasksCanBothBeRunning() {
        val data = task(
            taskId = DATA_TASK,
            queueKind = TaskQueueKind.DATA,
            operationId = DataTaskKind.ARCHIVE_BACKUP.name,
            phase = TaskLifecyclePhase.RUNNING,
        )
        val privilege = task(
            taskId = PRIVILEGE_TASK,
            queueKind = TaskQueueKind.PRIVILEGE,
            operationId = "FREEZE",
            phase = TaskLifecyclePhase.STOPPING,
        )
        setContent(
            QueueUiState(
                isLoading = false,
                running = RunningSectionUiState(data = data, privilege = privilege),
            ),
        )

        scrollToNode(hasTestTag(queueRowTag(DATA_TASK))).assertExists()
        scrollToNode(hasTestTag(queueRowTag(PRIVILEGE_TASK))).assertExists()
        assertText(R.string.task_operation_archive_backup)
        assertText(R.string.task_operation_freeze)
        scrollToNode(hasTestTag(queueRowTag(DATA_TASK)))
            .assertTextContains(rule.activity.getString(R.string.task_state_running))
        scrollToNode(hasTestTag(queueRowTag(PRIVILEGE_TASK)))
            .assertTextContains(rule.activity.getString(R.string.task_state_stopping))
        scrollToNode(hasTestTag(queueRowTag(DATA_TASK)))
            .assertTextContains(rule.activity.getString(R.string.task_queue_kind_data))
        scrollToNode(hasTestTag(queueRowTag(PRIVILEGE_TASK)))
            .assertTextContains(rule.activity.getString(R.string.task_queue_kind_privilege))
        scrollToNode(hasTestTag(queueRowTag(DATA_TASK)))
            .assertTextContains(rule.activity.getString(R.string.task_queue_progress, 1, 2))
    }

    @Test
    fun rowAndAdvertisedActionForwardExactIdentityWithoutClickThrough() {
        val task = task(
            taskId = DATA_TASK,
            queueKind = TaskQueueKind.DATA,
            operationId = DataTaskKind.ARCHIVE_RESTORE.name,
            phase = TaskLifecyclePhase.WAITING_FOR_SOURCE,
            actions = setOf(TaskAction.PROVIDE_SOURCE),
        )
        var selected: UUID? = null
        var performed: Pair<UUID, TaskAction>? = null
        setContent(
            state = QueueUiState(
                isLoading = false,
                queued = QueueLaneSectionUiState(data = listOf(task)),
            ),
            onTaskSelected = { selected = it },
            onAction = { taskId, action -> performed = taskId to action },
        )

        scrollToNode(hasTestTag(queueActionTag(DATA_TASK, TaskAction.PROVIDE_SOURCE)))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.runOnIdle {
            assertEquals(DATA_TASK to TaskAction.PROVIDE_SOURCE, performed)
            assertEquals(null, selected)
        }
        rule.onNodeWithTag(queueActionTag(DATA_TASK, TaskAction.CANCEL)).assertDoesNotExist()

        scrollToNode(hasTestTag(queueRowTag(DATA_TASK))).performClick()
        rule.runOnIdle { assertEquals(DATA_TASK, selected) }
    }

    @Test
    fun similarRowsRemainIndependentlySelectableByUuid() {
        val first = task(
            taskId = DATA_TASK,
            queueKind = TaskQueueKind.DATA,
            operationId = DataTaskKind.APP_EXPORT.name,
            phase = TaskLifecyclePhase.QUEUED,
        )
        val second = task(
            taskId = SECOND_DATA_TASK,
            queueKind = TaskQueueKind.DATA,
            operationId = DataTaskKind.APP_EXPORT.name,
            phase = TaskLifecyclePhase.QUEUED,
        )
        val selections = mutableListOf<UUID>()
        setContent(
            state = QueueUiState(
                isLoading = false,
                queued = QueueLaneSectionUiState(data = listOf(first, second)),
            ),
            onTaskSelected = selections::add,
        )

        scrollToNode(hasTestTag(queueRowTag(SECOND_DATA_TASK))).performClick()

        rule.runOnIdle { assertEquals(listOf(SECOND_DATA_TASK), selections) }
    }

    @Test
    fun backButtonHasAccessibleLabelAndInvokesOnce() {
        var backCount = 0
        setContent(
            state = QueueUiState(isLoading = false),
            onBack = { backCount++ },
        )

        rule.onNodeWithContentDescription(rule.activity.getString(R.string.cd_back))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.runOnIdle { assertEquals(1, backCount) }
    }

    private fun setContent(
        state: QueueUiState,
        onBack: () -> Unit = {},
        onTaskSelected: (UUID) -> Unit = {},
        onAction: (UUID, TaskAction) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            MaterialTheme {
                QueueContent(
                    state = state,
                    onBack = onBack,
                    onTaskSelected = onTaskSelected,
                    onAction = onAction,
                )
            }
        }
    }

    private fun assertText(stringRes: Int) {
        scrollToNode(hasText(rule.activity.getString(stringRes))).assertExists()
    }

    private fun scrollToNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return rule.onNode(matcher)
    }

    private fun task(
        taskId: UUID,
        queueKind: TaskQueueKind,
        operationId: String,
        phase: TaskLifecyclePhase,
        actions: Set<TaskAction> = emptySet(),
        titleArguments: List<String> = listOf("Example"),
        activeItemLabel: String? = "Example",
    ) = QueuedTaskSummary(
        taskId = taskId,
        queueKind = queueKind,
        operationId = operationId,
        titleArguments = titleArguments,
        sequence = taskId.leastSignificantBits,
        phase = phase,
        progress = TaskProgress(completed = 1, total = 2, stageLabel = null),
        activeItemLabel = activeItemLabel,
        actionRequirement = null,
        actions = actions,
        terminalAtEpochMs = null,
        retainUntilEpochMs = null,
        rootLaneDegraded = false,
    )

    private companion object {
        val DATA_TASK: UUID = UUID(0, 1)
        val SECOND_DATA_TASK: UUID = UUID(0, 2)
        val PRIVILEGE_TASK: UUID = UUID(0, 3)
    }
}
