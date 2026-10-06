// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.theme

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.model.FontPreset
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.model.minimumInstallTargetSdk
import com.valhalla.thor.presentation.installer.InstallerPreferencesContent
import com.valhalla.thor.presentation.installer.LegacyInstallConfirmationDialog
import com.valhalla.thor.presentation.settings.customization.FontPresetPickerRow
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings/installer components with local state only: no stored preferences or installs. */
@RunWith(AndroidJUnit4::class)
class FontPresetRenderingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pickerSwitchesOutfitAndFiraRolesToSystemFontsAndBack() {
        val selected = mutableStateOf(FontPreset.ASGARD)
        val choices = mutableListOf<FontPreset>()
        rule.setContent {
            ThorTheme(darkTheme = false, fontPreset = selected.value) {
                Surface(Modifier.fillMaxSize().testTag(PICKER_TAG)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        FontPresetPickerRow(
                            selectedPreset = selected.value,
                            onPresetSelected = {
                                choices += it
                                selected.value = it
                            },
                        )
                    }
                }
            }
        }

        val samples = linkedMapOf(
            "heading" to text(R.string.font_preview_heading),
            "body" to text(R.string.font_preview_body),
            "label" to text(R.string.font_preview_numbers),
        )
        val initial = verifyAndCapture("picker-asgard", FontPreset.ASGARD, samples)
        rule.onNodeWithTag(PICKER_TAG).savePng("picker-asgard.png")

        choice(R.string.font_preset_system).performScrollTo().performClick().assertIsOn()
        choice(R.string.font_preset_asgard).assertIsOff()
        val system = verifyAndCapture("picker-system", FontPreset.SYSTEM, samples)
        assertSameTypographyMetrics(initial, system)
        rule.onNodeWithTag(PICKER_TAG).savePng("picker-system.png")

        choice(R.string.font_preset_asgard).performScrollTo().performClick().assertIsOn()
        choice(R.string.font_preset_system).assertIsOff()
        val restored = verifyAndCapture("picker-asgard-restored", FontPreset.ASGARD, samples)
        assertSameTypographyMetrics(initial, restored)
        rule.runOnIdle { assertEquals(listOf(FontPreset.SYSTEM, FontPreset.ASGARD), choices) }
    }

    @Test
    fun installerDialogWaitsForSavedFontAndUpdatesEveryTypographyRole() {
        val preferences = mutableStateOf<UserPreferences?>(null)
        val metadata = AppMetadata(
            label = "Outfit font preview",
            packageName = "com.example.outfit.preview",
            version = "1.0",
            versionCode = 1,
            iconPath = null,
            targetSdk = 22,
        )
        rule.setContent {
            InstallerPreferencesContent(preferences = preferences.value) {
                // This is the real installer confirmation UI with inert callbacks. No APK is
                // parsed, no installer is invoked, and no permanent user preference is changed.
                LegacyInstallConfirmationDialog(
                    meta = metadata,
                    onConfirm = {},
                    onDismiss = {},
                    deviceSdk = 36,
                )
            }
        }

        text(R.string.legacy_install_confirm_title).assertDoesNotExist()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.log_initializing))
            .assertIsDisplayed()
        val message = rule.activity.getString(
            R.string.legacy_install_confirm_message,
            metadata.label, metadata.packageName, metadata.targetSdk,
            requireNotNull(minimumInstallTargetSdk(36)),
        )
        val samples = linkedMapOf(
            "heading" to text(R.string.legacy_install_confirm_title),
            "body" to rule.onNodeWithText(message, useUnmergedTree = true),
            "label" to text(R.string.legacy_install_allow_once),
        )

        rule.runOnIdle {
            preferences.value = UserPreferences(fontPreset = FontPreset.SYSTEM, themeMode = ThemeMode.LIGHT)
        }
        val system = verifyAndCapture("installer-system", FontPreset.SYSTEM, samples)
        rule.onNode(isDialog()).savePng("installer-system.png")

        rule.runOnIdle { preferences.value = preferences.value?.copy(fontPreset = FontPreset.ASGARD) }
        val asgard = verifyAndCapture("installer-asgard", FontPreset.ASGARD, samples)
        assertSameTypographyMetrics(system, asgard)
        rule.onNode(isDialog()).savePng("installer-asgard.png")

        rule.runOnIdle { preferences.value = preferences.value?.copy(fontPreset = FontPreset.SYSTEM) }
        val restored = verifyAndCapture("installer-system-restored", FontPreset.SYSTEM, samples)
        assertSameTypographyMetrics(system, restored)
    }

    private fun verifyAndCapture(
        name: String,
        preset: FontPreset,
        samples: Map<String, SemanticsNodeInteraction>,
    ): Map<String, TextLayoutResult> {
        val layouts = samples.mapValues { (role, node) ->
            node.assertIsDisplayed()
            val result = node.textLayout()
            val expected = when {
                preset == FontPreset.ASGARD && role == "label" -> firaMonoFontFamily
                preset == FontPreset.ASGARD -> bodyFontFamily
                else -> FontFamily.Default
            }
            assertEquals("$name $role family", expected, result.layoutInput.style.fontFamily)
            assertFalse("$name $role height overflow", result.didOverflowHeight)
            assertFalse("$name $role truncated", (0 until result.lineCount).any(result::isLineEllipsized))
            for (line in 0 until result.lineCount) {
                assertTrue(
                    "$name $role line does not fit",
                    result.getLineRight(line) - result.getLineLeft(line) <= result.size.width + 1f,
                )
            }
            result
        }
        File(evidenceDirectory(), "$name.csv").writeText(buildString {
            appendLine("role,preset,weight,font_size,line_height,letter_spacing,width_px,height_px,pixel_sha256")
            samples.forEach { (role, node) ->
                val result = layouts.getValue(role)
                val style = result.layoutInput.style
                val hash = node.savePng("$name-$role.png")
                appendLine(
                    "$role,${preset.name},${style.fontWeight?.weight},${style.fontSize}," +
                        "${style.lineHeight},${style.letterSpacing},${result.size.width},${result.size.height},$hash",
                )
            }
        })
        return layouts
    }

    private fun assertSameTypographyMetrics(
        before: Map<String, TextLayoutResult>,
        after: Map<String, TextLayoutResult>,
    ) {
        before.forEach { (role, original) ->
            val first = original.layoutInput.style
            val second = after.getValue(role).layoutInput.style
            assertEquals("$role weight", first.fontWeight, second.fontWeight)
            assertEquals("$role size", first.fontSize, second.fontSize)
            assertEquals("$role line height", first.lineHeight, second.lineHeight)
            assertEquals("$role letter spacing", first.letterSpacing, second.letterSpacing)
            assertEquals("$role style", first.fontStyle, second.fontStyle)
        }
    }

    private fun text(resource: Int) =
        rule.onNodeWithText(rule.activity.getString(resource), useUnmergedTree = true)

    private fun choice(resource: Int) =
        rule.onNodeWithText(rule.activity.getString(resource))

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
        assertTrue(action(layouts))
        return layouts.single()
    }

    private fun evidenceDirectory(): File =
        File(rule.activity.filesDir, "outfit-font-validation/ui")
            .also { check(it.isDirectory || it.mkdirs()) }

    private fun SemanticsNodeInteraction.savePng(name: String): String {
        val bitmap = captureToImage().asAndroidBitmap()
        try {
            File(evidenceDirectory(), name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES + Int.SIZE_BYTES * 2)
                .putInt(bitmap.width)
                .putInt(bitmap.height)
            pixels.forEach { bytes.putInt(it) }
            return MessageDigest.getInstance("SHA-256").digest(bytes.array())
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val PICKER_TAG = "outfit-preset-picker"
    }
}
