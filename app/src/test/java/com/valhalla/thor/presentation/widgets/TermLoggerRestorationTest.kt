// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.util.UiText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w400dp-h600dp-mdpi")
class TermLoggerRestorationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pausedOutputRemainsPausedAfterRestorationAndNewLogs() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val logs = mutableStateOf(List(100) { UiText.DynamicString("Line $it") })
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                TermLoggerContent(
                    title = UiText.DynamicString("Operation"), logs = logs.value,
                    status = TermLoggerStatus.NEUTRAL, modifier = Modifier.size(360.dp, 400.dp),
                )
            }
        }
        rule.onNodeWithTag(termLoggerLineTag(99)).assertIsDisplayed()
        rule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        rule.onNodeWithText(context.getString(R.string.log_jump_latest)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(context.getString(R.string.log_jump_latest)).assertIsDisplayed()
        rule.runOnIdle { logs.value += UiText.DynamicString("New output") }
        rule.onNodeWithText(context.getString(R.string.log_jump_latest_new_output))
            .assertIsDisplayed().performClick()
        rule.onNodeWithTag(termLoggerLineTag(100)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.log_jump_latest_new_output)).assertDoesNotExist()
    }
}
