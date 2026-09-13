// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.HomeActivity
import com.valhalla.thor.presentation.queue.taskDetailRouteTag
import java.util.UUID
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TaskQueueLaunchActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<HomeActivity>()

    @Test
    fun validTaskTapOpensExactTaskAndFinishesTrampoline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val trampolineMonitor = instrumentation.addMonitor(
            TaskQueueLaunchActivity::class.java.name,
            null,
            false,
        )

        try {
            context.startActivity(
                Intent(context, TaskQueueLaunchActivity::class.java)
                    .putExtra(TaskQueueLaunchActivity.EXTRA_TASK_ID, TASK_ID.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            val trampoline = instrumentation.waitForMonitorWithTimeout(
                trampolineMonitor,
                TIMEOUT_MS,
            )
            rule.waitUntilAtLeastOneExists(
                hasTestTag(taskDetailRouteTag(TASK_ID)),
                timeoutMillis = TIMEOUT_MS,
            )

            assertNotNull("task trampoline did not launch", trampoline)
            assertTrue(
                "task trampoline remained in history",
                requireNotNull(trampoline).isFinishing || trampoline.isDestroyed,
            )
        } finally {
            instrumentation.removeMonitor(trampolineMonitor)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val TASK_ID: UUID = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab")
    }
}
