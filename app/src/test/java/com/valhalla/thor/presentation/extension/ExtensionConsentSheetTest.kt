// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.extension

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtensionConsentSheetTest {

    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h480dp")
    fun compactPortraitCanScrollToAnswerAndConsent() {
        assertConsentReachableByScrolling()
    }

    @Test
    @Config(qualifiers = "en-rUS-w480dp-h320dp-land")
    fun compactLandscapeCanScrollToAnswerAndConsent() {
        assertConsentReachableByScrolling()
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h240dp")
    fun reducedHeightForKeyboardCanScrollToAnswerAndConsent() {
        // Constrain the available height as when the IME resizes the sheet's window.
        // This checks reachability in that viewport, not a platform keyboard implementation.
        assertConsentReachableByScrolling()
    }

    private fun assertConsentReachableByScrolling() {
        var accepted = 0
        val application = ApplicationProvider.getApplicationContext<Application>()

        rule.setContent {
            MaterialTheme {
                ExtensionConsentSheet(
                    onConsent = { accepted++ },
                    onDismiss = {},
                )
            }
        }

        val continueButton = rule.onNodeWithText(application.getString(R.string.extension_consent_accept))
        val answerField = rule.onNode(hasSetTextAction())
        continueButton.assertIsNotEnabled()
        answerField.performScrollTo()
            .assertIsDisplayed()
            .performClick()
            .performTextInput("0")

        continueButton.performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(0, accepted) }

        // Read the displayed challenge so the test also exercises the sheet's saved operands.
        val prompt = rule.onNodeWithText("To confirm", substring = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val operands = Regex("(\\d+) \\+ (\\d+)").find(prompt)!!.groupValues
        val correctAnswer = operands[1].toInt() + operands[2].toInt()
        answerField.performScrollTo().assertIsDisplayed().performTextReplacement(correctAnswer.toString())

        continueButton.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, accepted) }
    }
}
