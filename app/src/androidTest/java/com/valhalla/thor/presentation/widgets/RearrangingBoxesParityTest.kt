// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.RenderMode
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel oracle is the original Lottie animation, not a second copy of the Canvas implementation. */
@RunWith(AndroidJUnit4::class)
class RearrangingBoxesParityTest {
    @Test
    fun lightAnimationMatchesLottieThroughoutTheLoop() = verifyTheme(darkTheme = false)

    @Test
    fun darkAnimationMatchesLottieThroughoutTheLoop() = verifyTheme(darkTheme = true)

    private fun verifyTheme(darkTheme: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val theme = if (darkTheme) "dark" else "light"
        val asset = "animations/rearranging-boxes-$theme-reference.json"
        val jsonBytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
        val parsed = LottieCompositionFactory.fromJsonInputStreamSync(jsonBytes.inputStream(), null)
        val composition = requireNotNull(parsed.value) { "Cannot parse $asset: ${parsed.exception}" }
        val json = JSONObject(jsonBytes.toString(Charsets.UTF_8))
        val progressSamples = progressSamples(composition, json)
        val output = File(instrumentation.targetContext.filesDir, "rearranging-boxes-parity/$theme")
        check(output.isDirectory || output.mkdirs())
        val metadata = JSONObject()
            .put("device", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT)
            .put("referenceAsset", asset)
            .put("referenceSha256", sha256(jsonBytes))
            .put("referenceVersion", "Lottie 6.7.1")
            .put("canonicalWidth", json.getInt("w"))
            .put("canonicalHeight", json.getInt("h"))
            .put("parsedBoundsWidth", composition.bounds.width())
            .put("parsedBoundsHeight", composition.bounds.height())
            .put("startFrame", composition.startFrame)
            .put("endFrame", composition.endFrame)
            .put("durationFrames", composition.durationFrames)
            .put("samplesPerSize", progressSamples.size)
            .put("targetPixelSizes", JSONArray(PIXEL_SIZES))
            .put("meanPremultipliedChannelErrorLimit", MEAN_ERROR_LIMIT)
            .put("pixelsWithErrorOver16FractionLimit", OUTLIER_FRACTION_LIMIT)
            .put("relativeAlphaAreaErrorLimit", AREA_ERROR_LIMIT)
            .put("centroidDistancePixelLimit", CENTROID_ERROR_LIMIT)
            .put("inkBoundsEdgePixelLimit", BOUNDS_ERROR_LIMIT)
        File(output, "metadata.json").writeText(metadata.toString(2))

        val renderer = RearrangingBoxesRenderer()
        val failures = mutableListOf<String>()
        val summary = mutableListOf<String>()
        File(output, "frames.csv").bufferedWriter().use { csv ->
            csv.appendLine("pixels,progress,composition_frame,mean_premultiplied_channel_error,max_channel_error,outlier_fraction,relative_alpha_area_error,centroid_distance_px,bounds_edge_error_px,reference_alpha_area,candidate_alpha_area,pass")
            PIXEL_SIZES.forEach { size ->
                // Lottie parses coordinates/bounds using device dpScale. setBounds uses those
                // parsed bounds internally, so the source's 1080 design units are never mistaken
                // for pixels. HARDWARE selects direct Canvas paths even on this software bitmap,
                // avoiding an extra offscreen bitmap resize in Lottie's SOFTWARE rendering path.
                val reference = LottieDrawable().apply {
                    setComposition(composition)
                    setRenderMode(RenderMode.HARDWARE)
                    setAsyncUpdates(AsyncUpdates.DISABLED)
                    setUseCompositionFrameRate(false)
                    setBounds(0, 0, size, size)
                }
                val before = newBitmap(size)
                val after = newBitmap(size)
                var failedAtSize = 0
                var worstMean = 0.0
                var worstCentroid = 0.0
                try {
                    progressSamples.forEachIndexed { index, progress ->
                        before.eraseColor(Color.TRANSPARENT)
                        after.eraseColor(Color.TRANSPARENT)
                        reference.progress = progress
                        reference.draw(Canvas(before))
                        renderer.draw(Canvas(after), size, size, progress, darkTheme)
                        val stats = compare(before, after)
                        worstMean = max(worstMean, stats.meanError)
                        worstCentroid = max(worstCentroid, stats.centroidError)
                        csv.appendLine(String.format(
                            Locale.ROOT, "%d,%.9f,%.6f,%.6f,%.3f,%.8f,%.8f,%.6f,%d,%.4f,%.4f,%s",
                            size, progress, composition.getFrameForProgress(progress), stats.meanError,
                            stats.maxError, stats.outlierFraction, stats.areaError, stats.centroidError,
                            stats.boundsError, stats.referenceArea, stats.candidateArea, stats.passed,
                        ))
                        if (!stats.passed) {
                            failedAtSize++
                            if (failures.size < 12) {
                                failures += "$theme ${size}px p=$progress: $stats"
                            }
                            // Preserve the first two mismatches at every density; keep the full
                            // numeric sweep even when a frame fails so diagnosis is not truncated.
                            if (failedAtSize <= 2) {
                                val stem = "failure-${size}px-sample-$index"
                                save(before, File(output, "$stem-reference.png"))
                                save(after, File(output, "$stem-candidate.png"))
                                difference(before, after).useBitmap { save(it, File(output, "$stem-diff-x8.png")) }
                            }
                        }
                    }
                    summary += "${size}px: ${progressSamples.size - failedAtSize}/${progressSamples.size} pass; " +
                        "max mean channel error=$worstMean; max centroid error=$worstCentroid px"
                    if (size == 150) writeContactSheet(output, reference, renderer, darkTheme)
                } finally {
                    before.recycle()
                    after.recycle()
                    reference.clearComposition()
                }
            }
        }
        File(output, "summary.txt").writeText(summary.joinToString("\n", postfix = "\n"))
        assertTrue(
            "Canvas/Lottie parity failed; artifacts: $output\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * Sweep half-frame intervals and both sides of authored keyframe/time-map boundaries.
     * The JSON only chooses sampling times; every expected pixel still comes from LottieDrawable.
     * Its top-level time map has diagonal easing handles (linear). Lottie 6.7.1 uses the original
     * duration for remapping and duration - 0.01 for keyframe progress; preserve that distinction
     * when finding times to sample, including the last time-map segment near the loop boundary.
     */
    private fun progressSamples(composition: LottieComposition, json: JSONObject): List<Float> {
        val samples = sortedSetOf<Float>()
        fun addBoundary(progress: Float) {
            if (progress !in 0f..1f) return
            listOf(-BOUNDARY_EPSILON, 0f, BOUNDARY_EPSILON).forEach { delta ->
                samples += (progress + delta).coerceIn(0f, 1f)
            }
        }
        val halfFrames = ceil(composition.durationFrames * 2).toInt()
        (0..halfFrames).forEach { samples += it.toFloat() / halfFrames }
        CONTACT_PROGRESS.forEach { samples += it }
        val timeMap = (0 until json.getJSONArray("layers").length())
            .map { json.getJSONArray("layers").getJSONObject(it) }
            .single { it.has("tm") }.getJSONObject("tm").getJSONArray("k")
        val keyframes = (0 until timeMap.length()).map(timeMap::getJSONObject)
        keyframes.forEach { keyframe ->
            addBoundary((keyframe.getDouble("t").toFloat() - composition.startFrame) / composition.durationFrames)
            listOf("o", "i").filter(keyframe::has).forEach { handle ->
                val easing = keyframe.getJSONObject(handle)
                check(easing.getDouble("x") == easing.getDouble("y")) {
                    "Reference time map changed; update boundary sampling for nonlinear easing"
                }
            }
        }
        collectKeyframeTimes(json.getJSONArray("assets")).forEach { childFrame ->
            val childProgress = (childFrame - composition.startFrame) / composition.durationFrames
            val seconds = (childProgress * (composition.durationFrames + 0.01f) + composition.startFrame) /
                composition.frameRate
            keyframes.zipWithNext().forEach { (start, end) ->
                val startSeconds = start.getJSONArray("s").getDouble(0).toFloat()
                val endSeconds = end.getJSONArray("s").getDouble(0).toFloat()
                if (seconds in startSeconds..endSeconds && endSeconds > startSeconds) {
                    val fraction = (seconds - startSeconds) / (endSeconds - startSeconds)
                    val parentFrame = start.getDouble("t").toFloat() + fraction *
                        (end.getDouble("t").toFloat() - start.getDouble("t").toFloat())
                    addBoundary((parentFrame - composition.startFrame) / composition.durationFrames)
                }
            }
        }
        addBoundary(0f)
        addBoundary(1f)
        return samples.toList()
    }

    private fun collectKeyframeTimes(value: Any): Set<Float> = buildSet {
        when (value) {
            is JSONObject -> {
                if (value.opt("t") is Number && value.has("s")) add(value.getDouble("t").toFloat())
                value.keys().forEach { key -> addAll(collectKeyframeTimes(value.get(key))) }
            }
            is JSONArray -> (0 until value.length()).forEach { addAll(collectKeyframeTimes(value.get(it))) }
        }
    }

    private fun compare(reference: Bitmap, candidate: Bitmap): Stats {
        val first = pixels(reference)
        val second = pixels(candidate)
        var errorSum = 0.0
        var maxError = 0.0
        var outliers = 0
        val referenceInk = Ink(reference.width, reference.height)
        val candidateInk = Ink(candidate.width, candidate.height)
        first.indices.forEach { index ->
            referenceInk.add(first[index], index)
            candidateInk.add(second[index], index)
            val alpha1 = Color.alpha(first[index])
            val alpha2 = Color.alpha(second[index])
            val alphaError = abs(alpha1 - alpha2).toDouble()
            val redError = abs(Color.red(first[index]) * alpha1 / 255.0 - Color.red(second[index]) * alpha2 / 255.0)
            val greenError = abs(Color.green(first[index]) * alpha1 / 255.0 - Color.green(second[index]) * alpha2 / 255.0)
            val blueError = abs(Color.blue(first[index]) * alpha1 / 255.0 - Color.blue(second[index]) * alpha2 / 255.0)
            errorSum += alphaError + redError + greenError + blueError
            val pixelError = max(max(alphaError, redError), max(greenError, blueError))
            maxError = max(maxError, pixelError)
            if (pixelError > 16) outliers++
        }
        val referenceArea = referenceInk.mass / 255.0
        val candidateArea = candidateInk.mass / 255.0
        val areaError = if (referenceArea > 0) abs(candidateArea - referenceArea) / referenceArea else Double.POSITIVE_INFINITY
        val centroid = if (referenceInk.mass > 0 && candidateInk.mass > 0) {
            hypot(referenceInk.x / referenceInk.mass - candidateInk.x / candidateInk.mass,
                referenceInk.y / referenceInk.mass - candidateInk.y / candidateInk.mass)
        } else Double.POSITIVE_INFINITY
        val boundsError = maxOf(abs(referenceInk.left - candidateInk.left), abs(referenceInk.right - candidateInk.right),
            abs(referenceInk.top - candidateInk.top), abs(referenceInk.bottom - candidateInk.bottom))
        return Stats(errorSum / (first.size * 4), maxError, outliers.toDouble() / first.size,
            areaError, centroid, boundsError, referenceArea, candidateArea)
    }

    /** Compare alpha and premultiplied RGB so invisible RGB values cannot manufacture differences. */
    private fun channelErrors(first: Int, second: Int): DoubleArray {
        val alpha1 = Color.alpha(first)
        val alpha2 = Color.alpha(second)
        return doubleArrayOf(
            abs(alpha1 - alpha2).toDouble(),
            abs(Color.red(first) * alpha1 / 255.0 - Color.red(second) * alpha2 / 255.0),
            abs(Color.green(first) * alpha1 / 255.0 - Color.green(second) * alpha2 / 255.0),
            abs(Color.blue(first) * alpha1 / 255.0 - Color.blue(second) * alpha2 / 255.0),
        )
    }

    private fun writeContactSheet(output: File, reference: LottieDrawable, renderer: RearrangingBoxesRenderer, dark: Boolean) {
        val size = 150
        val labelWidth = 160
        val header = 45
        val sheet = createBitmap(labelWidth + size * 3, header + size * CONTACT_PROGRESS.size)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(if (dark) Color.rgb(24, 24, 24) else Color.WHITE)
            val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else Color.BLACK; textSize = 16f }
            canvas.drawText("Progress", 8f, 27f, labels)
            canvas.drawText("Lottie", labelWidth + 8f, 27f, labels)
            canvas.drawText("Canvas", labelWidth + size + 8f, 27f, labels)
            canvas.drawText("Diff x8", labelWidth + size * 2 + 8f, 27f, labels)
            CONTACT_PROGRESS.forEachIndexed { index, progress ->
                newBitmap(size).useBitmap { first ->
                    newBitmap(size).useBitmap { second ->
                        reference.progress = progress
                        reference.draw(Canvas(first))
                        renderer.draw(Canvas(second), size, size, progress, dark)
                        val top = (header + index * size).toFloat()
                        canvas.drawText(String.format(Locale.ROOT, "%.6f", progress), 8f, top + 35f, labels)
                        canvas.drawBitmap(first, labelWidth.toFloat(), top, null)
                        canvas.drawBitmap(second, (labelWidth + size).toFloat(), top, null)
                        difference(first, second).useBitmap { diff ->
                            canvas.drawBitmap(diff, (labelWidth + size * 2).toFloat(), top, null)
                        }
                    }
                }
            }
            save(sheet, File(output, "contact-sheet-150px.png"))
        } finally {
            sheet.recycle()
        }
    }

    private fun difference(first: Bitmap, second: Bitmap): Bitmap {
        val before = pixels(first)
        val after = pixels(second)
        val delta = IntArray(before.size) { index ->
            val channels = channelErrors(before[index], after[index])
            val value = min(255, (channels.max() * 8).toInt())
            Color.rgb(value, 0, value)
        }
        return newBitmap(first.width).apply { setPixels(delta, 0, width, 0, 0, width, height) }
    }

    private fun newBitmap(size: Int) = createBitmap(size, size).apply {
        density = Bitmap.DENSITY_NONE
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) = try { block(this) } finally { recycle() }
    private fun save(bitmap: Bitmap, file: File) = file.outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    private class Ink(private val width: Int, height: Int) {
        var mass = 0.0
        var x = 0.0
        var y = 0.0
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        fun add(pixel: Int, index: Int) {
            val alpha = Color.alpha(pixel)
            val px = index % width
            val py = index / width
            mass += alpha
            x += px * alpha.toDouble()
            y += py * alpha.toDouble()
            if (alpha >= 8) {
                left = min(left, px)
                right = max(right, px)
                top = min(top, py)
                bottom = max(bottom, py)
            }
        }
    }

    private data class Stats(
        val meanError: Double,
        val maxError: Double,
        val outlierFraction: Double,
        val areaError: Double,
        val centroidError: Double,
        val boundsError: Int,
        val referenceArea: Double,
        val candidateArea: Double,
    ) {
        val passed get() = meanError <= MEAN_ERROR_LIMIT && outlierFraction <= OUTLIER_FRACTION_LIMIT &&
            areaError <= AREA_ERROR_LIMIT && centroidError <= CENTROID_ERROR_LIMIT && boundsError <= BOUNDS_ERROR_LIMIT
    }

    private companion object {
        // Exactly 50dp at mdpi, hdpi, xhdpi, 2.75x, xxhdpi and xxxhdpi, with pixel rounding.
        val PIXEL_SIZES = listOf(50, 75, 100, 138, 150, 200)
        val CONTACT_PROGRESS = listOf(0f, 0.125f, 0.25f, 0.5f, 0.75f, 0.875f, (109f - 13f) / 96.99f, 1f)
        const val BOUNDARY_EPSILON = 0.00001f
        const val MEAN_ERROR_LIMIT = 0.35
        const val OUTLIER_FRACTION_LIMIT = 0.005
        const val AREA_ERROR_LIMIT = 0.01
        const val CENTROID_ERROR_LIMIT = 0.35
        const val BOUNDS_ERROR_LIMIT = 1
    }
}
