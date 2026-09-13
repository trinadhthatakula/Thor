// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w800dp-h1280dp")
// Real font metrics are necessary to detect translated labels overflowing their layout bounds.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueTaskRowLayoutTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun partialStatusProgressAndQueueKindStaySeparateInAllLocalesAt320dpAndDoubleText() {
        assertLocalizedRowsFit(width = 320.dp, fontScale = 2f)
    }

    @Test
    fun partialStatusProgressAndQueueKindStaySeparateInAllLocalesAt600dpAndLargeText() {
        assertLocalizedRowsFit(width = 600.dp, fontScale = 1.5f)
    }

    private fun assertLocalizedRowsFit(width: Dp, fontScale: Float) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val localizedContexts = localeTags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            application.createConfigurationContext(
                Configuration(application.resources.configuration).apply {
                    setLocale(locale)
                    setLayoutDirection(locale)
                },
            )
        }
        val selectedContext = mutableStateOf(localizedContexts.first())
        val tasks = listOf(partialTask(TaskQueueKind.DATA), partialTask(TaskQueueKind.PRIVILEGE))

        rule.setContent {
            val context = selectedContext.value
            val direction = if (context.resources.configuration.locales[0].language == "ar") {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalLayoutDirection provides direction,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme {
                    Surface(Modifier.width(width).height(600.dp).testTag(VIEWPORT_TAG)) {
                        // Match QueueContent's 16dp horizontal list padding, not an artificially
                        // wider standalone card. A scrollable body keeps longer locales reachable.
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            tasks.forEach { task ->
                                QueueTaskRow(task = task, onSelected = {}, onAction = {})
                            }
                        }
                    }
                }
            }
        }

        localizedContexts.forEach { context ->
            rule.runOnIdle { selectedContext.value = context }
            val locale = context.resources.configuration.locales[0]
            val direction = if (locale.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            val case = "locale=${locale.toLanguageTag()}, width=$width, fontScale=$fontScale"
            rule.onNodeWithTag(VIEWPORT_TAG).assertWidthIsEqualTo(width)

            tasks.forEach { task ->
                val phase = rowText(task, context.getString(R.string.task_state_partial))
                val progress = rowText(task, context.getString(R.string.task_queue_progress, 7L, 12L))
                val kind = rowText(task, context.getString(task.queueKind.labelRes()))
                listOf(phase, progress, kind).forEach { it.assertHasNoTruncation(case, direction) }

                val rowBounds = rule.onNodeWithTag(queueRowTag(task.taskId)).getUnclippedBoundsInRoot()
                val phaseBounds = phase.getUnclippedBoundsInRoot()
                val progressBounds = progress.getUnclippedBoundsInRoot()
                val kindBounds = kind.getUnclippedBoundsInRoot()
                assertTrue(
                    "$case: progress must be below the complete status, not beside it: $phaseBounds / $progressBounds",
                    progressBounds.top > phaseBounds.bottom,
                )
                assertTrue(
                    "$case: queue kind must be on its own line below progress: $progressBounds / $kindBounds",
                    kindBounds.top > progressBounds.bottom,
                )
                listOf(phaseBounds, progressBounds, kindBounds).forEach { bounds ->
                    assertTrue(
                        "$case: $bounds must stay inside row $rowBounds",
                        bounds.left >= rowBounds.left && bounds.right <= rowBounds.right &&
                            bounds.top >= rowBounds.top && bounds.bottom <= rowBounds.bottom,
                    )
                }
            }

            rowText(tasks.last(), context.getString(R.string.task_queue_kind_privilege))
                .performScrollTo().assertIsDisplayed()
        }
    }

    private fun rowText(task: QueuedTaskSummary, text: String) = rule.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(queueRowTag(task.taskId))),
        useUnmergedTree = true,
    )

    private fun SemanticsNodeInteraction.assertHasNoTruncation(case: String, direction: LayoutDirection) {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
        assertTrue(action(layouts))
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        val diagnostic = "$case, text=${layout.layoutInput.text}, size=${layout.size}, " +
            "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
            "paragraphWidth=${layout.multiParagraph.width}, paragraphHeight=${layout.multiParagraph.height}, " +
            "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}"
        assertEquals(diagnostic, direction, layout.layoutInput.layoutDirection)
        assertFalse(diagnostic, layout.hasVisualOverflow)
        assertFalse(diagnostic, layout.didOverflowWidth)
        assertFalse(diagnostic, layout.didOverflowHeight)
        assertFalse(diagnostic, (0 until layout.lineCount).any(layout::isLineEllipsized))
    }

    private fun partialTask(kind: TaskQueueKind) = QueuedTaskSummary(
        taskId = UUID(0, if (kind == TaskQueueKind.DATA) 1L else 2L),
        queueKind = kind,
        operationId = if (kind == TaskQueueKind.DATA) "ARCHIVE_RESTORE" else "FREEZE",
        titleArguments = listOf("Example"),
        sequence = 1,
        phase = TaskLifecyclePhase.PARTIAL,
        progress = TaskProgress(7, 12, null),
        activeItemLabel = null,
        actionRequirement = null,
        actions = setOf(TaskAction.ACKNOWLEDGE),
        terminalAtEpochMs = 1_000,
        retainUntilEpochMs = 10_000,
        rootLaneDegraded = false,
    )

    private fun TaskQueueKind.labelRes() = when (this) {
        TaskQueueKind.DATA -> R.string.task_queue_kind_data
        TaskQueueKind.PRIVILEGE -> R.string.task_queue_kind_privilege
    }

    private companion object {
        const val VIEWPORT_TAG = "queue-row-viewport"
        val localeTags = listOf("en", "ar", "es", "fr", "pl", "pt", "pt-BR", "zh-CN")
    }
}
