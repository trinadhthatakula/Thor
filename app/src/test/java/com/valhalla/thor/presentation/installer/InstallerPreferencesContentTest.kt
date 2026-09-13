// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FontPreset
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.theme.bodyFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class InstallerPreferencesContentTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun waitingForPreferencesShowsAccessibleProgressThenUsesTheSavedFontForEveryBodyComposition() {
        val preferences = mutableStateOf<UserPreferences?>(null)
        val bodyFamilies = mutableListOf<FontFamily?>()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loadingDescription = context.getString(R.string.log_initializing)

        rule.setContent {
            InstallerPreferencesContent(preferences = preferences.value) {
                val style = MaterialTheme.typography.bodyLarge
                SideEffect { bodyFamilies += style.fontFamily }
                Text(BODY_TEXT, style = style)
            }
        }

        rule.onNodeWithContentDescription(loadingDescription)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                ),
            )
        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        rule.onNodeWithText(BODY_TEXT).assertDoesNotExist()
        rule.runOnIdle {
            assertTrue("The installer body must wait for saved preferences", bodyFamilies.isEmpty())
            preferences.value = UserPreferences(fontPreset = FontPreset.SYSTEM)
        }

        rule.onNodeWithText(BODY_TEXT).assertIsDisplayed()
        rule.onNodeWithContentDescription(loadingDescription).assertDoesNotExist()
        rule.runOnIdle {
            assertFalse(bodyFamilies.isEmpty())
            bodyFamilies.forEach { family -> assertEquals(FontFamily.Default, family) }
        }
    }

    @Test
    fun liveFontChangesKeepTheReadyInstallerBodyAndItsRememberedState() {
        val preferences = mutableStateOf(UserPreferences(fontPreset = FontPreset.SYSTEM))
        var observedFamily: FontFamily? = null
        var bodyIdentity: Any? = null

        rule.setContent {
            InstallerPreferencesContent(preferences = preferences.value) {
                val identity = remember { Any() }
                var count by remember { mutableIntStateOf(0) }
                val style = MaterialTheme.typography.bodyLarge
                SideEffect {
                    observedFamily = style.fontFamily
                    bodyIdentity = identity
                }
                Text(
                    text = "$BODY_TEXT: $count",
                    modifier = Modifier.testTag(BODY_TAG).clickable { count++ },
                    style = style,
                )
            }
        }

        var originalIdentity: Any? = null
        rule.runOnIdle {
            assertEquals(FontFamily.Default, observedFamily)
            assertNotNull(bodyIdentity)
            originalIdentity = bodyIdentity
        }
        rule.onNodeWithTag(BODY_TAG).performClick().assertTextEquals("$BODY_TEXT: 1")

        rule.runOnIdle {
            preferences.value = preferences.value.copy(fontPreset = FontPreset.ASGARD)
        }
        rule.onNodeWithTag(BODY_TAG).assertIsDisplayed().assertTextEquals("$BODY_TEXT: 1")
        rule.runOnIdle {
            assertEquals(bodyFontFamily, observedFamily)
            assertSame(originalIdentity, bodyIdentity)
            preferences.value = preferences.value.copy(fontPreset = FontPreset.SYSTEM)
        }
        rule.onNodeWithTag(BODY_TAG).assertTextEquals("$BODY_TEXT: 1")
        rule.runOnIdle {
            assertEquals(FontFamily.Default, observedFamily)
            assertSame(originalIdentity, bodyIdentity)
        }
    }

    @Test
    @Config(sdk = [28, 31])
    fun installerManifestThemeRemainsTranslucentOnAndroid9And12() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, PortableInstallerActivity::class.java),
            0,
        )
        val theme = context.resources.newTheme().apply {
            applyStyle(activityInfo.themeResource, true)
        }
        val attributes = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        try {
            assertTrue(
                "The portable installer must retain its translucent manifest theme",
                attributes.getBoolean(0, false),
            )
        } finally {
            attributes.recycle()
        }
    }

    private companion object {
        const val BODY_TEXT = "Installer content"
        const val BODY_TAG = "installer-body"
    }
}
