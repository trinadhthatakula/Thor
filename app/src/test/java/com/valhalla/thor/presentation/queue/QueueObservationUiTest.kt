// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
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
}
