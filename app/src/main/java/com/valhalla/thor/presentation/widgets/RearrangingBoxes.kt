// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.content.res.Resources
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.withMatrix
import com.valhalla.thor.presentation.theme.LocalDarkTheme
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** The original terminal's seven rearranging boxes, with its existing theme and loop timing. */
@Composable
fun RearrangingBoxesAnimation(modifier: Modifier = Modifier) {
    val darkTheme = LocalDarkTheme.current
    val density = LocalDensity.current.density
    val renderer = remember(density) { RearrangingBoxesRenderer() }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val motionScale = coroutineContext[MotionDurationScale]
        var previousFrameNanos = Long.MIN_VALUE
        while (true) {
            if (motionScale?.scaleFactor == 0f) {
                // Lottie stops at the final frame when system animations are disabled. Suspend
                // until motion returns, instead of keeping an invisible infinite frame loop alive.
                progress = 1f
                snapshotFlow { motionScale?.scaleFactor ?: 1f }.first { it > 0f }
                previousFrameNanos = Long.MIN_VALUE
            }
            withInfiniteAnimationFrameNanos { frameNanos ->
                val scale = motionScale?.scaleFactor ?: 1f
                val previous = previousFrameNanos
                if (scale == 0f) {
                    progress = 1f
                } else if (previous != Long.MIN_VALUE) {
                    // Match LottieAnimatable 6.7.1's whole-millisecond frame deltas, rather than
                    // changing the visible speed by switching to a nanosecond-based tween.
                    val deltaMillis = (frameNanos - previous) / 1_000_000L
                    val next = progress + deltaMillis / BOXES_DURATION_MILLIS * (1f / scale)
                    progress = if (next < 1f) {
                        next
                    } else {
                        val pastEnd = next - 1f
                        pastEnd - pastEnd.toInt()
                    }
                }
                previousFrameNanos = frameNanos
            }
        }
    }

    Canvas(modifier) {
        // Read progress during drawing so animation frames do not recompose the terminal.
        drawIntoCanvas { canvas ->
            renderer.draw(
                canvas.nativeCanvas,
                size.width.roundToInt(),
                size.height.roundToInt(),
                progress,
                darkTheme,
            )
        }
    }
}

/**
 * Deterministic renderer for the original rearrange.json composition, not a general animation
 * parser. Its fixed-progress entry point lets device tests compare the original and new renderers.
 *
 * The source is a 1080-square precomposition rotated 225.1 degrees, with seven identical shapes.
 * Crucially, its outer 13..109.99 frame clip time-remaps the child animation; playing the child
 * keyframes directly changes both the motion and the loop seam. Float operation order, density
 * scaling, Android PathInterpolator and PathMeasure follow the original Lottie 6.7.1 renderer.
 * Instances own mutable drawing scratch objects and must be used on one drawing thread.
 */
internal class RearrangingBoxesRenderer {
    private val density = Resources.getSystem().displayMetrics.density
    private val compositionSize = (1080f * density).toInt()
    private val center = 540f * density
    private val shapeScale = 18.493f / 100f
    private val commonEasing = PathInterpolator(0.714f, 0f, 0.218f, 1f)
    private val linearEasing = PathInterpolator(0.167f, 0.167f, 0.833f, 0.833f)
    private val firstReturnEasing = PathInterpolator(0.742f, 0f, 0.186f, 1f)
    private val secondReturnEasing = PathInterpolator(0.756f, 0f, 0.189f, 1f)
    private val thirdReturnEasing = PathInterpolator(0.546f, 0f, 0.218f, 1f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 74f * density
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeMiter = 4f
    }
    private val shape = roundedSquare()
    private val transformedFill = Path()
    private val renderMatrix = Matrix()
    private val parentMatrix = Matrix()
    private val positionMatrix = Matrix()
    private val shapeMatrix = Matrix()
    private val groupMatrix = Matrix().apply { preScale(shapeScale, shapeScale) }
    private val precompositionMatrix = Matrix().apply {
        preTranslate(center, center)
        preRotate(225.1f)
        preScale(42f / 100f, 42f / 100f)
        preTranslate(-center, -center)
    }
    private val clip = RectF()
    private val position = FloatArray(2)

    // Reverse of the JSON layer array: After Effects/Lottie paint the backmost layer first.
    private val tracks = listOf(
        track(
            key(0f, 540f, 540f, outX = -0.083f, outY = 50.042f, inX = 0.083f, inY = -50.042f),
            key(34.363f, 539.5f, 840.25f, easing = linearEasing),
            key(48.109f, 539.5f, 840.25f, outX = -50.042f, inX = 50.042f, easing = firstReturnEasing),
            key(80.181640625f, 239.25f, 840.25f),
        ),
        track(
            key(6.873f, 839.5f, 540f),
            key(41.236f, 540f, 540f, easing = linearEasing),
            key(56f, 540f, 540f, inX = 0.083f, inY = -50.042f, easing = secondReturnEasing),
            key(89.091796875f, 539.5f, 840.25f),
        ),
        track(
            key(13.746f, 839.5f, 239.5f, outY = 50.083f, inY = -50.083f),
            key(48.109f, 839.5f, 540f, easing = linearEasing),
            key(64.908f, 839.5f, 540f, outX = -49.917f, easing = thirdReturnEasing),
            key(98f, 540f, 540f),
        ),
        track(
            key(20.617f, 540f, 239.5f, outX = 49.917f, inX = -49.917f),
            key(54.98046875f, 839.5f, 239.5f),
        ),
        track(
            key(27.49f, 239.25f, 239.5f, outX = 50.125f, inX = -50.125f),
            key(61.853515625f, 540f, 239.5f),
        ),
        track(
            key(34.363f, 239.25f, 540f, outY = -50.083f, inY = 50.083f),
            key(68.7265625f, 239.25f, 239.5f),
        ),
        track(
            key(41.236f, 239.25f, 840.25f, outY = -50.042f, inY = 50.042f),
            key(75.599609375f, 239.25f, 540f),
        ),
    )

    fun draw(canvas: AndroidCanvas, width: Int, height: Int, progress: Float, darkTheme: Boolean) {
        if (width <= 0 || height <= 0) return
        val scale = max(width.toFloat() / compositionSize, height.toFloat() / compositionSize)
        val scaledSize = (compositionSize * scale).roundToInt()
        renderMatrix.reset()
        // ContentScale.Crop with Alignment.Center, including its pixel-rounded placement.
        renderMatrix.preTranslate(
            ((width - scaledSize) / 2f).roundToInt().toFloat(),
            ((height - scaledSize) / 2f).roundToInt().toFloat(),
        )
        renderMatrix.preScale(scale, scale)
        parentMatrix.set(renderMatrix)
        parentMatrix.preConcat(precompositionMatrix)
        fillPaint.color = if (darkTheme) Color.rgb(254, 254, 254) else Color.BLACK
        strokePaint.color = if (darkTheme) Color.WHITE else Color.BLACK
        val childProgress = remappedProgress(progress.coerceIn(0f, 1f))

        val saveCount = canvas.save()
        try {
            clip.set(0f, 0f, compositionSize.toFloat(), compositionSize.toFloat())
            renderMatrix.mapRect(clip)
            canvas.clipRect(clip)
            clip.set(0f, 0f, compositionSize.toFloat(), compositionSize.toFloat())
            parentMatrix.mapRect(clip)
            canvas.clipRect(clip)

            for (track in tracks) {
                track.positionAt(childProgress, position)
                positionMatrix.reset()
                positionMatrix.preTranslate(position[0], position[1])
                shapeMatrix.set(parentMatrix)
                shapeMatrix.preConcat(positionMatrix)
                shapeMatrix.preConcat(groupMatrix)

                // Lottie transforms the fill path, but transforms the canvas for its stroke.
                // Keeping these separate also preserves the thin stroke's rasterization.
                transformedFill.reset()
                transformedFill.addPath(shape, shapeMatrix)
                canvas.drawPath(transformedFill, fillPaint)
                canvas.withMatrix(shapeMatrix) {
                    drawPath(shape, strokePaint)
                }
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun remappedProgress(progress: Float): Float {
        // LottieDrawable.setProgress converts progress to a frame; its animator converts it back.
        val frame = BOXES_START_FRAME + progress * BOXES_DURATION_FRAMES
        val drawableProgress = (frame - BOXES_START_FRAME) / BOXES_DURATION_FRAMES
        val interval = if (frame < 109f) TIME_REMAP_MAIN else TIME_REMAP_END
        val fraction = linearEasing.getInterpolation(interval.fraction(drawableProgress))
        val remappedSeconds = if (frame < 109f) {
            0.217f + fraction * (1.15f - 0.217f)
        } else {
            1.15f + fraction * (5.017f - 1.15f)
        }
        // Unlike ordinary keyframes, CompositionLayer restores the parser's 0.01-frame offset
        // in this denominator. Dropping that distinction shifts the original motion slightly.
        return (remappedSeconds * 60f - BOXES_START_FRAME) / (BOXES_DURATION_FRAMES + 0.01f)
    }

    private fun roundedSquare(): Path {
        val half = 1080f * density / 2f
        val radius = 150f * density
        val diameter = 2f * radius
        val arc = RectF()
        return Path().apply {
            // Same clockwise line/arc contour as Lottie's RectangleContent, avoiding a different
            // addRoundRect representation at the very small size used by the terminal.
            moveTo(half, -half + radius)
            lineTo(half, half - radius)
            arc.set(half - diameter, half - diameter, half, half)
            arcTo(arc, 0f, 90f, false)
            lineTo(-half + radius, half)
            arc.set(-half, half - diameter, -half + diameter, half)
            arcTo(arc, 90f, 90f, false)
            lineTo(-half, -half + radius)
            arc.set(-half, -half, -half + diameter, -half + diameter)
            arcTo(arc, 180f, 90f, false)
            lineTo(half - radius, -half)
            arc.set(half - diameter, -half, half, -half + diameter)
            arcTo(arc, 270f, 90f, false)
            close()
        }
    }

    private fun key(
        frame: Float,
        x: Float,
        y: Float,
        outX: Float = 0f,
        outY: Float = 0f,
        inX: Float = 0f,
        inY: Float = 0f,
        easing: PathInterpolator = commonEasing,
    ) = PositionKey(
        frame, x * density, y * density,
        outX * density, outY * density, inX * density, inY * density, easing,
    )

    private fun track(vararg keys: PositionKey) = PositionTrack(
        keys.asList().zipWithNext { start, end -> PositionSegment(start, end) },
    )

    private class PositionKey(
        val frame: Float,
        val x: Float,
        val y: Float,
        val outX: Float,
        val outY: Float,
        val inX: Float,
        val inY: Float,
        val easing: PathInterpolator,
    )

    private class PositionSegment(private val start: PositionKey, end: PositionKey) {
        val interval = FrameInterval(start.frame, end.frame)
        private val measure = if (start.x == end.x && start.y == end.y) {
            null
        } else {
            PathMeasure(Path().apply {
                moveTo(start.x, start.y)
                if (start.outX != 0f || start.outY != 0f || start.inX != 0f || start.inY != 0f) {
                    cubicTo(
                        start.x + start.outX, start.y + start.outY,
                        end.x + start.inX, end.y + start.inY,
                        end.x, end.y,
                    )
                } else {
                    lineTo(end.x, end.y)
                }
            }, false)
        }

        fun positionAt(progress: Float, result: FloatArray) {
            if (measure == null) {
                result[0] = start.x
                result[1] = start.y
            } else {
                val fraction = start.easing.getInterpolation(interval.fraction(progress))
                measure.getPosTan(fraction * measure.length, result, null)
            }
        }
    }

    private class PositionTrack(private val segments: List<PositionSegment>) {
        fun positionAt(progress: Float, result: FloatArray) {
            val clamped = progress.coerceIn(segments.first().interval.start, segments.last().interval.end)
            val segment = segments.lastOrNull { clamped >= it.interval.start } ?: segments.first()
            segment.positionAt(clamped, result)
        }
    }
}

/** Mirrors the float/double normalization used by Lottie's Keyframe start/end progress. */
private class FrameInterval(startFrame: Float, endFrame: Float) {
    val start = (startFrame - BOXES_START_FRAME) / BOXES_DURATION_FRAMES
    val end = (start.toDouble() + (endFrame - startFrame) / BOXES_DURATION_FRAMES.toDouble()).toFloat()

    fun fraction(progress: Float): Float = (progress - start) / (end - start)
}

private const val BOXES_START_FRAME = 13f
private const val BOXES_DURATION_FRAMES = (110f - 0.01f) - BOXES_START_FRAME
private const val BOXES_DURATION_MILLIS = 1616f
private val TIME_REMAP_MAIN = FrameInterval(13f, 109f)
private val TIME_REMAP_END = FrameInterval(109f, 301f)
