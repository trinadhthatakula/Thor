// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.model.AppMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InstallerVersionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(code: Long, fontScale: Float, update: Boolean = true) {
        val state = InstallState.ReadyToInstall(
            meta = AppMetadata("App", "example.app", "2.0", code, null, baseApkSha256 = "abcdef01".repeat(8)),
            isUpdate = update,
            oldVersion = "1.0",
            oldVersionCode = code - 1,
        )
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(260.dp)) { InstallerVersions(state) }
                }
            }
        }
    }

    private fun node(text: String) = rule.onNodeWithText(text, useUnmergedTree = true)

    private fun bounds(text: String) = node(text).fetchSemanticsNode().boundsInRoot

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertWholeLine(text: String) {
        val layout = layoutOf(text)
        assertFalse("Overflow in $text", layout.hasVisualOverflow)
        assertEquals(1, layout.lineCount)
        assertEquals(text.length, layout.getLineEnd(0, visibleEnd = true))
    }

    @Test fun shortUpdateFitsSideBySideWithEachFingerprintBelowItsCode() {
        show(code = 42, fontScale = 1f)
        assertEquals(bounds("(41)").top, bounds("(42)").top)
        assertTrue(bounds("(41)").right < bounds("(42)").left)
        assertTrue(bounds("[]").top >= bounds("(41)").bottom)
        assertTrue(bounds("[abcdef01]").top >= bounds("(42)").bottom)
    }

    @Test fun longUpdateStacksAndPreservesEveryDigitAtLargeFontScale() {
        show(code = Long.MAX_VALUE, fontScale = 2f)
        assertTrue(bounds("2.0").top >= bounds("[]").bottom)
        for (code in listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE)) assertWholeLine("($code)")
        assertTrue(bounds("[abcdef01]").top >= bounds("(${Long.MAX_VALUE})").bottom)
        assertWholeLine("[abcdef01]")
    }

    @Test fun longFreshInstallUsesTheWholeCardWidth() {
        show(code = Long.MAX_VALUE, fontScale = 2f, update = false)
        assertWholeLine("(${Long.MAX_VALUE})")
        assertTrue(bounds("[abcdef01]").top >= bounds("(${Long.MAX_VALUE})").bottom)
    }

    @Test fun downgradeExplanationKeepsBothCodesTogetherAtLargeFontScale() {
        val installed = Long.MAX_VALUE
        val incoming = installed - 1
        val text = rule.activity.getString(
            R.string.install_downgrade_code_explainer, incoming.toString(), installed.toString(),
        )
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(230.dp)) { DowngradeExplanation(incoming, installed) }
                }
            }
        }
        val layout = layoutOf(text)
        for (code in listOf(incoming.toString(), installed.toString())) {
            val start = text.indexOf(code)
            assertEquals(layout.getLineForOffset(start), layout.getLineForOffset(start + code.lastIndex))
        }
    }
}
