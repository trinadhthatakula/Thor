// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FontPreset
import com.valhalla.thor.presentation.theme.ThorTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w800dp-h1280dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontPresetPickerRowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun selectingAPresetUpdatesThePreviewWithoutAddingPretendActions() {
        val selected = mutableStateOf(FontPreset.ASGARD)
        val changes = mutableListOf<FontPreset>()
        val context = ApplicationProvider.getApplicationContext<Application>()

        rule.setContent {
            ThorTheme(fontPreset = selected.value) {
                Surface(Modifier.width(360.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                        FontPresetPickerRow(
                            selectedPreset = selected.value,
                            onPresetSelected = {
                                changes += it
                                selected.value = it
                            },
                            modifier = Modifier.testTag(PREVIEW_TAG),
                        )
                    }
                }
            }
        }

        val asgard = rule.onNodeWithText(context.getString(R.string.font_preset_asgard))
        val system = rule.onNodeWithText(context.getString(R.string.font_preset_system))
        val asgardFamilies = previewStrings.map { resId ->
            rule.onNodeWithText(context.getString(resId), useUnmergedTree = true)
                .textLayout().layoutInput.style.fontFamily
        }
        capturePreviewIfRequested("asgard")

        asgard.assertIsOn().performClick()
        system.assertIsOff().performClick()
        system.assertIsOn()
        asgard.assertIsOff()

        previewStrings.forEachIndexed { index, resId ->
            val sample = rule.onNodeWithText(context.getString(resId), useUnmergedTree = true)
            assertNotEquals(asgardFamilies[index], sample.textLayout().layoutInput.style.fontFamily)
        }
        capturePreviewIfRequested("system")

        // The only actions are the actual preset choices; the preview is readable sample text.
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
        asgard.performClick().assertIsOn()
        previewStrings.forEachIndexed { index, resId ->
            assertEquals(
                asgardFamilies[index],
                rule.onNodeWithText(context.getString(resId), useUnmergedTree = true)
                    .textLayout().layoutInput.style.fontFamily,
            )
        }
        rule.runOnIdle { assertEquals(listOf(FontPreset.SYSTEM, FontPreset.ASGARD), changes) }
    }

    @Test
    fun allLocalizedChoicesAndPreviewTextFitAtDoubleTextSizeIncludingRtl() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val contexts = localeTags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val configuration = Configuration(application.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            application.createConfigurationContext(configuration)
        }
        val selectedContext = mutableStateOf(contexts.first())
        val selectedPreset = mutableStateOf(FontPreset.ASGARD)

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
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            ) {
                ThorTheme(fontPreset = selectedPreset.value) {
                    Surface(Modifier.width(320.dp).height(600.dp).testTag(VIEWPORT_TAG)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                            FontPresetPickerRow(
                                selectedPreset = selectedPreset.value,
                                onPresetSelected = { selectedPreset.value = it },
                            )
                        }
                    }
                }
            }
        }

        contexts.forEach { context ->
            FontPreset.entries.forEach { preset ->
                rule.runOnIdle {
                    selectedContext.value = context
                    selectedPreset.value = preset
                }
                val locale = context.resources.configuration.locales[0]
                val direction = if (locale.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
                val case = "locale=${locale.toLanguageTag()}, preset=$preset, width=320dp, fontScale=2"
                if (direction == LayoutDirection.Rtl && !System.getProperty("thor.fontPreviewDir").isNullOrBlank()) {
                    rule.onNodeWithText(context.getString(R.string.customization_fonts), useUnmergedTree = true)
                        .performScrollTo()
                    capturePreviewIfRequested("ar-${preset.name.lowercase(Locale.ROOT)}", VIEWPORT_TAG)
                }
                allStrings.forEach { resId ->
                    val sample = rule.onNodeWithText(context.getString(resId), useUnmergedTree = true)
                    val layout = sample.textLayout()
                    val diagnostic = "$case, text=${layout.layoutInput.text}, size=${layout.size}, " +
                        "constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}, " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                        "intrinsicWidth=${layout.multiParagraph.maxIntrinsicWidth}, " +
                        "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
                        "lines=" + (0 until layout.lineCount).joinToString { line ->
                            "[$line: ${layout.getLineLeft(line)}..${layout.getLineRight(line)}, " +
                                "end=${layout.getLineEnd(line)}, ellipsized=${layout.isLineEllipsized(line)}]"
                        }
                    assertEquals(diagnostic, direction, layout.layoutInput.layoutDirection)
                    // Simple Text paints a cached Paragraph, but its semantics rebuild a
                    // MultiParagraph using the wider available constraint and the original node
                    // size. That synthetic paragraph can be 87px wide for a 57px label, shifting
                    // RTL line positions even though the painted paragraph fits. Compare line
                    // extents, not that reconstructed alignment origin, with the actual node.
                    assertFalse(diagnostic, layout.didOverflowHeight)
                    assertFalse(diagnostic, (0 until layout.lineCount).any(layout::isLineEllipsized))
                    assertEquals(diagnostic, layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                    for (line in 0 until layout.lineCount) {
                        val lineWidth = layout.getLineRight(line) - layout.getLineLeft(line)
                        assertTrue(diagnostic, lineWidth <= layout.size.width + 0.5f)
                        assertTrue(diagnostic, layout.getLineTop(line) >= -0.5f)
                        assertTrue(diagnostic, layout.getLineBottom(line) <= layout.size.height + 0.5f)
                    }
                }
                rule.onNodeWithText(context.getString(R.string.font_preview_numbers), useUnmergedTree = true)
                    .performScrollTo()
                    .assertIsDisplayed()
            }
        }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
        assertTrue(action(layouts))
        return layouts.single()
    }

    /** Optional host-side artifacts; ordinary test runs never create files. */
    private fun capturePreviewIfRequested(name: String, tag: String = PREVIEW_TAG) {
        val path = System.getProperty("thor.fontPreviewDir")?.takeIf { it.isNotBlank() } ?: return
        val directory = File(path)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory: $path" }
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Cannot save $name preview" }
        }
    }

    private companion object {
        const val PREVIEW_TAG = "font-preset-preview"
        const val VIEWPORT_TAG = "font-preset-viewport"
        val previewStrings = listOf(
            R.string.font_preview_heading,
            R.string.font_preview_body,
            R.string.font_preview_numbers,
        )
        val allStrings = listOf(
            R.string.customization_fonts,
            R.string.customization_fonts_desc,
            R.string.font_preset_asgard,
            R.string.font_preset_system,
            R.string.font_preview_title,
        ) + previewStrings
        val localeTags = listOf("en", "ar", "es", "fr", "pl", "pt", "pt-BR", "zh-CN")
    }
}
