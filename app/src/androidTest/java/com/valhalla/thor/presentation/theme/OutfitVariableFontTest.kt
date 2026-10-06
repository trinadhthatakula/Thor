// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.theme

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Exercises the production family through Compose's resolver, layout and rasterization. Merely
 * registering nine FontWeights does not select a variable font's wght axis on every Android release.
 * Disabling synthesis makes identical/default outlines a failure instead of hiding them with fake
 * bold. The same tests also run against the old static family for before/after device evidence.
 */
@RunWith(AndroidJUnit4::class)
class OutfitVariableFontTest {
    @Test
    fun normalWeightsRenderDistinctOutlinesAtBothFontScales() {
        FONT_SCALES.forEach { scale ->
            val renders = renderWeights(FontStyle.Normal, scale)
            try {
                writeEvidence("normal", scale, renders)
                assertWeightProgression("normal, fontScale=$scale", renders)
            } finally {
                renders.forEach { it.bitmap.recycle() }
            }
        }
    }

    @Test
    fun italicAliasesRetainEveryWeightWithoutInventingAnItalicDesign() {
        // Outfit has no italic/slant axis. Thor has always registered its upright faces for italic
        // requests too; preserve that behavior and each requested weight with synthesis disabled.
        FONT_SCALES.forEach { scale ->
            val normal = renderWeights(FontStyle.Normal, scale)
            val italic = renderWeights(FontStyle.Italic, scale)
            try {
                writeEvidence("italic", scale, italic)
                assertWeightProgression("italic, fontScale=$scale", italic)
                normal.zip(italic).forEach { (upright, alias) ->
                    assertEquals(
                        "Italic alias changed the upright outline at ${alias.weight}, scale=$scale",
                        upright.alphaSha256,
                        alias.alphaSha256,
                    )
                }
            } finally {
                (normal + italic).forEach { it.bitmap.recycle() }
            }
        }
    }

    @Test
    fun largerFontScaleIncreasesRenderedSizeForEveryWeight() {
        val normal = renderWeights(FontStyle.Normal, 1f)
        val larger = renderWeights(FontStyle.Normal, 1.3f)
        try {
            normal.zip(larger).forEach { (small, large) ->
                assertTrue(
                    "fontScale did not increase text width at weight ${small.weight}",
                    large.layoutWidth > small.layoutWidth * 1.2,
                )
                assertTrue(
                    "fontScale did not increase rendered ink at weight ${small.weight}",
                    large.inkCoverage > small.inkCoverage * 1.4,
                )
            }
        } finally {
            (normal + larger).forEach { it.bitmap.recycle() }
        }
    }

    private fun renderWeights(style: FontStyle, fontScale: Float): List<RenderedWeight> {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration).apply {
            this.fontScale = fontScale
            // Test exact requested weights even if the device has accessibility bold text enabled.
            // This isolated context does not change the user's device or app configuration.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) fontWeightAdjustment = 0
        }
        val context = target.createConfigurationContext(configuration)
        val measurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(density = 2f, fontScale = fontScale),
            defaultLayoutDirection = LayoutDirection.Ltr,
            cacheSize = 0,
        )

        return WEIGHTS.map { weight ->
            val result = measurer.measure(
                text = AnnotatedString(SAMPLE),
                style = TextStyle(
                    fontFamily = bodyFontFamily,
                    fontWeight = FontWeight(weight),
                    fontStyle = style,
                    fontSynthesis = FontSynthesis.None,
                    // Use body-sized text: Android's nonlinear scaling deliberately leaves 40sp
                    // unchanged at scale 1.3. Density 2 keeps the outlines large enough to inspect.
                    fontSize = 16.sp,
                    color = ComposeColor.Black,
                ),
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(
                    maxWidth = RENDER_WIDTH - PADDING * 2,
                    maxHeight = RENDER_HEIGHT - PADDING * 2,
                ),
            )
            assertFalse("Specimen overflow at weight=$weight, scale=$fontScale", result.hasVisualOverflow)

            val bitmap = createBitmap(RENDER_WIDTH, RENDER_HEIGHT)
            val canvas = ComposeCanvas(bitmap.asImageBitmap())
            canvas.translate(PADDING.toFloat(), PADDING.toFloat())
            result.multiParagraph.paint(canvas = canvas, color = ComposeColor.Black)

            val pixels = IntArray(RENDER_WIDTH * RENDER_HEIGHT)
            bitmap.getPixels(pixels, 0, RENDER_WIDTH, 0, 0, RENDER_WIDTH, RENDER_HEIGHT)
            val alpha = ByteArray(pixels.size) { index -> (pixels[index] ushr 24).toByte() }
            val ink = pixels.sumOf { (it ushr 24).toLong() } / 255.0
            assertTrue("Empty specimen at weight=$weight, scale=$fontScale", ink > 0)
            // Ensure increasing coverage cannot be an artifact of text getting clipped at an edge.
            assertTrue(
                "Clipped specimen at weight=$weight, scale=$fontScale",
                pixels.indices.none { index ->
                    val x = index % RENDER_WIDTH
                    val y = index / RENDER_WIDTH
                    (x == 0 || x == RENDER_WIDTH - 1 || y == 0 || y == RENDER_HEIGHT - 1) &&
                        (pixels[index] ushr 24) != 0
                },
            )
            RenderedWeight(
                weight = weight,
                bitmap = bitmap,
                inkCoverage = ink,
                layoutWidth = result.size.width,
                layoutHeight = result.size.height,
                firstBaseline = result.firstBaseline,
                alphaSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(alpha).joinToString("") { "%02x".format(it) },
            )
        }
    }

    private fun assertWeightProgression(label: String, renders: List<RenderedWeight>) {
        assertEquals(
            "$label: weights resolved to identical outlines; check the explicit wght axes",
            WEIGHTS.size,
            renders.map { it.alphaSha256 }.distinct().size,
        )
        renders.zipWithNext().forEach { (lighter, heavier) ->
            assertTrue(
                "$label: ${heavier.weight} must have more ink than ${lighter.weight}, " +
                    "but coverage was ${heavier.inkCoverage} vs ${lighter.inkCoverage}",
                heavier.inkCoverage > lighter.inkCoverage * 1.01,
            )
        }
    }

    private fun writeEvidence(style: String, fontScale: Float, renders: List<RenderedWeight>) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        // Internal debug files are readable with run-as on every test API, without storage/root grants.
        val output = File(target.filesDir, "outfit-font-validation")
        check(output.isDirectory || output.mkdirs()) { "Cannot create evidence directory: $output" }
        val stem = "outfit-$style-scale-${String.format(Locale.ROOT, "%.1f", fontScale)}"
        val csv = File(output, "$stem.csv")
        csv.writeText(buildString {
            appendLine("weight,style,font_scale,ink_coverage_px,layout_width_px,layout_height_px,baseline_px,alpha_sha256")
            renders.forEach { render ->
                appendLine(String.format(
                    Locale.ROOT,
                    "%d,%s,%.1f,%.4f,%d,%d,%.4f,%s",
                    render.weight, style, fontScale, render.inkCoverage, render.layoutWidth,
                    render.layoutHeight, render.firstBaseline, render.alphaSha256,
                ))
            }
        })

        val headerHeight = 100
        val labelWidth = 210
        val specimen = createBitmap(
            RENDER_WIDTH + labelWidth,
            headerHeight + RENDER_HEIGHT * renders.size,
        )
        try {
            val canvas = Canvas(specimen)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
            }
            canvas.drawText("Outfit: $style, fontScale=$fontScale, synthesis=None", 16f, 34f, paint)
            paint.textSize = 19f
            canvas.drawText("${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", 16f, 65f, paint)
            renders.forEachIndexed { index, render ->
                val y = (headerHeight + index * RENDER_HEIGHT).toFloat()
                paint.color = Color.rgb(224, 224, 224)
                canvas.drawLine(0f, y, specimen.width.toFloat(), y, paint)
                paint.color = Color.BLACK
                paint.textSize = 25f
                canvas.drawText("${render.weight} ${WEIGHT_NAMES[index]}", 16f, y + 36f, paint)
                paint.textSize = 17f
                canvas.drawText(String.format(Locale.ROOT, "ink %.1f px", render.inkCoverage), 16f, y + 63f, paint)
                canvas.drawBitmap(render.bitmap, labelWidth.toFloat(), y, null)
            }
            File(output, "$stem.png").outputStream().use { stream ->
                check(specimen.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            specimen.recycle()
        }
        Log.i(TAG, "Evidence: ${File(output, "$stem.png")} and $csv")
    }

    private data class RenderedWeight(
        val weight: Int,
        val bitmap: Bitmap,
        val inkCoverage: Double,
        val layoutWidth: Int,
        val layoutHeight: Int,
        val firstBaseline: Float,
        val alphaSha256: String,
    )

    private companion object {
        const val TAG = "OutfitVariableFontTest"
        const val SAMPLE = "Hamburgefontsiv Aa 0123456789"
        const val RENDER_WIDTH = 1180
        const val RENDER_HEIGHT = 128
        const val PADDING = 8
        val WEIGHTS = (100..900 step 100).toList()
        val WEIGHT_NAMES = listOf("Thin", "ExtraLight", "Light", "Regular", "Medium", "SemiBold", "Bold", "ExtraBold", "Black")
        val FONT_SCALES = listOf(1f, 1.3f)
    }
}
