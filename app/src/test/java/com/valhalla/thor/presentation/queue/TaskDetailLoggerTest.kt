// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.util.UiText
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class TaskDetailLoggerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun aggregateUsesFullQuantityOnceAndDoesNotAccumulate() {
        for (count in listOf(1, 63, 64, 65, 80)) {
            val state = state(lines = listOf(line("TASK_PENDING_COUNT", count.toString())))
            repeat(3) {
                val quantities = state.loggerLines().filterIsInstance<UiText.PluralsResource>()
                assertEquals(listOf(count), quantities.map { it.quantity })
                assertEquals(if (count == 1) "1 task accepted and queued" else "$count tasks accepted and queued",
                    quantities.single().asString(context))
            }
        }
    }

    @Test fun onlyRecognizedPendingFixturesAggregateAfterInformativeRows() {
        for (code in listOf("TASK_ITEM_PENDING", "SWEEP_TARGET_PENDING")) {
            val projected = state(lines = listOf(
                line(code, "app.first"), line("UNRECOGNIZED_PENDING", "app.hidden"),
                line("TASK_ITEM_RUNNING", "app.running"), line(code, "app.second"),
            )).loggerLines()
            assertEquals(listOf(UiText.StringResource(R.string.task_log_item_running, "app.running"),
                UiText.PluralsResource(pluralId("task_log_pending_queued"), 2)), projected.drop(2))
            assertEquals("1 task accepted and queued", state(lines = listOf(line(code, "app.only")))
                .loggerLines().last().asString(context))
        }
    }

    @Test fun phaseWordingNeverPromisesExecutionForStoppedBlockedOrTerminalWork() {
        val terminal = setOf(TaskLifecyclePhase.SUCCEEDED, TaskLifecyclePhase.PARTIAL,
            TaskLifecyclePhase.FAILED, TaskLifecyclePhase.CANCELLED, TaskLifecyclePhase.EXPIRED,
            TaskLifecyclePhase.READY, TaskLifecyclePhase.READY_PARTIAL)
        val active = setOf(TaskLifecyclePhase.STARTING, TaskLifecyclePhase.QUEUED, TaskLifecyclePhase.RUNNING)
        for (phase in TaskLifecyclePhase.entries) {
            val projected = state(phase, listOf(line("TASK_PENDING_COUNT", "63"))).loggerLines()
            val quantities = projected.filterIsInstance<UiText.PluralsResource>()
            assertEquals(phase.name, 1, quantities.size)
            val count = quantities.single()
            val expected = when (phase) {
                in active -> "63 tasks accepted and queued"
                in terminal -> "63 tasks not processed"
                else -> "63 tasks pending"
            }
            assertEquals(phase.name, expected, count.asString(context))
        }
    }

    @Test fun zeroMalformedAndUnknownCountsStayHidden() {
        for (argument in listOf("0", "-1", "not_a_count", "2147483648")) {
            assertEquals(2, state(lines = listOf(line("TASK_PENDING_COUNT", argument))).loggerLines().size)
        }
        assertEquals(2, state(lines = listOf(line("UNRECOGNIZED_DIAGNOSTIC", "app.hidden"))).loggerLines().size)
        assertEquals(2, state(lines = emptyList()).loggerLines().size)
    }

    @Test fun outcomesOmissionStageReasonAndDegradedWarningRemainSeparate() {
        val codes = listOf("TASK_ITEM_RUNNING", "TASK_ITEM_SUCCEEDED", "TASK_ITEM_FAILED", "TASK_ITEM_CANCELLED",
            "SWEEP_TARGET_RUNNING", "SWEEP_TARGET_SUCCEEDED", "SWEEP_TARGET_FAILED", "SWEEP_TARGET_CANCELLED",
            "SWEEP_TARGET_BUSY", "SWEEP_TARGET_UNKNOWN", "SWEEP_TARGET_LEGACY_UNKNOWN")
        val resources = listOf(R.string.task_log_item_running, R.string.task_log_item_succeeded,
            R.string.task_log_item_failed, R.string.task_log_item_cancelled, R.string.task_log_item_running,
            R.string.task_log_item_succeeded, R.string.task_log_item_failed, R.string.task_log_item_cancelled,
            R.string.task_log_item_busy, R.string.task_log_item_unknown, R.string.task_log_item_unknown)
        val initial = state(TaskLifecyclePhase.STOPPING, listOf(line("TASK_RESULTS_OMITTED", "17")) +
            codes.map { line(it, "app.test") } + line("TASK_PENDING_COUNT", "1"))
        val detailed = initial.copy(detail = initial.detail!!.copy(summary = initial.summary!!.copy(
            progress = TaskProgress(11, 80, DataTaskStage.WRITING.name), rootLaneDegraded = true,
        )))
        val projected = detailed.loggerLines()
        assertEquals(UiText.StringResource(R.string.task_log_stage_writing), projected[2])
        assertEquals("17 earlier results omitted", projected[3].asString(context))
        assertEquals(resources.map { UiText.StringResource(it, "app.test") }, projected.subList(4, 15))
        assertEquals("1 task pending", projected[15].asString(context))
        assertEquals(UiText.StringResource(R.string.task_reason_stopping), projected[16])
        assertEquals(UiText.StringResource(R.string.task_reason_root_lane_degraded), projected[17])
    }

    @Test fun quantitiesAreDefinedAndResolveAcrossAllEightLocales() {
        val locales = mapOf("values" to "en", "values-ar" to "ar", "values-es" to "es",
            "values-fr" to "fr", "values-pl" to "pl", "values-pt" to "pt",
            "values-pt-rBR" to "pt-BR", "values-zh-rCN" to "zh-CN")
        val resRoot = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val names = listOf("task_log_pending_queued", "task_log_pending_neutral",
            "task_log_pending_unprocessed", "task_log_results_omitted")
        for ((directory, locale) in locales) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(resRoot, "$directory/strings.xml"))
            val plurals = document.getElementsByTagName("plurals")
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            val localized = context.createConfigurationContext(config)
            for (name in names) {
                val element = (0 until plurals.length).map { plurals.item(it) }
                    .singleOrNull { it.attributes.getNamedItem("name").nodeValue == name }
                assertNotNull("$directory defines $name", element)
                val items = requireNotNull(element).childNodes
                val quantities = (0 until items.length).map { items.item(it) }.filter { it.nodeName == "item" }
                assertTrue("$directory/$name has other", quantities.any { it.attributes.getNamedItem("quantity").nodeValue == "other" })
                assertTrue("$directory/$name keeps a numeric placeholder", quantities.all { it.textContent.contains(Regex("%([1]\\$)?d")) })
                for (count in listOf(1, 2, 5, 63, 80)) {
                    val text = UiText.PluralsResource(pluralId(name), count).asString(localized)
                    assertFalse(text.isBlank())
                    assertFalse(text.contains("%"))
                }
            }
        }
    }

    private fun pluralId(name: String): Int = when (name) {
        "task_log_pending_queued" -> R.plurals.task_log_pending_queued
        "task_log_pending_neutral" -> R.plurals.task_log_pending_neutral
        "task_log_pending_unprocessed" -> R.plurals.task_log_pending_unprocessed
        "task_log_results_omitted" -> R.plurals.task_log_results_omitted
        else -> error("Unknown quantity resource $name")
    }

    private fun line(code: String, argument: String) = TaskLogLine(0, code, listOf(argument))

    private fun state(
        phase: TaskLifecyclePhase = TaskLifecyclePhase.RUNNING,
        lines: List<TaskLogLine>,
    ): TaskDetailUiState {
        val id = UUID(0, 42)
        val summary = QueuedTaskSummary(id, TaskQueueKind.PRIVILEGE, PrivilegeSweepOperation.REINSTALL.name,
            emptyList(), 1, phase, TaskProgress(0, 80, null), null, null,
            TaskActionPolicy.actionsFor(phase, null), null, null, false)
        return TaskDetailUiState(id, phase = phase, detail = QueuedTaskDetail(summary, lines, null, emptyList()))
    }
}
