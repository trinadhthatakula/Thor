// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.settingseditor

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsEditorConsentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun warningRequiresCorrectAnswerAndScrollsToAcceptance() {
        var accepted = 0
        rule.setContent { MaterialTheme { SettingsEditorConsentSheet(false, false, { accepted++ }, {}) } }
        val acceptText = rule.activity.getString(R.string.extension_consent_accept)
        rule.onNodeWithText(acceptText).performScrollTo().assertIsNotEnabled()
        val prompt = rule.onNode(hasText("solve:", substring = true)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        val terms = Regex("(\\d+) \\+ (\\d+)").find(prompt)!!
        val answer = terms.groupValues[1].toInt() + terms.groupValues[2].toInt()
        val field = rule.onNode(hasSetTextAction())
        field.performScrollTo().performTextInput("0")
        rule.onNodeWithText(acceptText).performScrollTo().assertIsNotEnabled()
        field.performScrollTo().performTextReplacement(answer.toString())
        rule.onNodeWithText(acceptText).performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, accepted) }
    }
    @Test fun failedPersistenceKeepsWarningVisible() {
        rule.setContent { MaterialTheme { SettingsEditorConsentSheet(false, true, {}, {}) } }
        rule.onNodeWithText(rule.activity.getString(R.string.sett_consent_failed)).performScrollTo().assertIsDisplayed()
    }
}
