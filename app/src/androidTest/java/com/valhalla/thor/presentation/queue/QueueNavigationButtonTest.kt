// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueNavigationButtonTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun queueButtonIsAccessibleAndForwardsTheExactClick() {
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                QueueNavigationButton(onClick = { clicks++ })
            }
        }

        rule.onNodeWithTag(QUEUE_NAVIGATION_BUTTON_TAG)
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals(rule.activity.getString(R.string.task_queue_title))
            .performClick()

        rule.runOnIdle { assertEquals(1, clicks) }
    }
}
