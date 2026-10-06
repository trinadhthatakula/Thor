// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.presentation.theme.LocalDarkTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the actual composable's clock, disposal and theme wiring. The sibling static renderer
 * is a fixed-progress oracle; its geometry is independently checked against Lottie by the parity
 * tests. No system motion preference, user setting or production database is changed here.
 */
@RunWith(AndroidJUnit4::class)
class RearrangingBoxesAnimationTest {
    private val motionScale = TestMotionDurationScale()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(effectContext = motionScale),
    )

    private var visible by mutableStateOf(true)
    private var darkTheme by mutableStateOf(false)
    private var referenceProgress by mutableFloatStateOf(0f)
    private var referenceDarkTheme by mutableStateOf(false)

    @Test
    fun advancesWithOriginalDurationAndWrapsTheLoop() {
        mount()
        assertFrame(0f)
        val initial = capture(ACTUAL)
        try {
            advanceFrames(25)
            assertFrame(400f / DURATION_MILLIS)
            assertChanged(initial, "The active animation did not advance")

            // 25 + 80 test frames = 1680 ms: cross the 1616 ms loop boundary with remainder.
            advanceFrames(80)
            assertFrame(64f / DURATION_MILLIS)
        } finally {
            initial.recycle()
        }
    }

    @Test
    fun motionScaleSlowsPlaybackAndZeroScaleSuspendsThenResumes() {
        mount(scale = 2f)
        advanceFrames(25)
        assertFrame(200f / DURATION_MILLIS)

        rule.runOnIdle { motionScale.value = 0f }
        advanceFrames(1)
        assertFrame(1f)
        val readsWhileStopped = motionScale.reads.get()
        advanceFrames(40)
        assertFrame(1f)
        assertEquals(
            "Zero motion scale must suspend instead of requesting continuous animation frames",
            readsWhileStopped,
            motionScale.reads.get(),
        )

        rule.runOnIdle { motionScale.value = 1f }
        // Resuming establishes a fresh first-frame timestamp, rather than charging the stopped time.
        advanceFrames(1)
        assertFrame(1f)
        advanceFrames(25)
        assertFrame(400f / DURATION_MILLIS)
    }

    @Test
    fun leavingCompositionStopsTheClockAndReentryStartsAtTheBeginning() {
        mount()
        advanceFrames(25)
        assertFrame(400f / DURATION_MILLIS)

        rule.runOnIdle { visible = false }
        advanceFrames(1)
        rule.onNodeWithTag(ACTUAL).assertDoesNotExist()
        val readsAfterRemoval = motionScale.reads.get()
        advanceFrames(40)
        assertEquals(
            "Removed animation must not keep a frame coroutine alive",
            readsAfterRemoval,
            motionScale.reads.get(),
        )

        rule.runOnIdle { visible = true }
        // One frame applies the composition change; the next initializes the new animation clock.
        advanceFrames(2)
        assertFrame(0f)
        advanceFrames(25)
        assertFrame(400f / DURATION_MILLIS)
    }

    @Test
    fun zeroScaleStartsAtFinalFrameAndInAppThemeChangesItsColors() {
        mount(scale = 0f)
        assertFrame(1f)
        val light = capture(ACTUAL)
        try {
            rule.runOnIdle {
                darkTheme = true
                referenceDarkTheme = true
            }
            advanceFrames(1)
            assertFrame(1f)
            assertChanged(light, "The animation ignored the in-app dark theme")

            rule.runOnIdle {
                darkTheme = false
                referenceDarkTheme = false
            }
            advanceFrames(1)
            assertFrame(1f)
            capture(ACTUAL).useBitmap { restored ->
                assertEquivalent(light, restored, "Returning to the light theme")
            }
        } finally {
            light.recycle()
        }
    }

    private fun mount(scale: Float = 1f) {
        motionScale.value = scale
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val referenceRenderer = remember { RearrangingBoxesRenderer() }
            Column {
                CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
                    if (visible) {
                        RearrangingBoxesAnimation(
                            Modifier.size(96.dp).background(BACKGROUND).testTag(ACTUAL),
                        )
                    }
                }
                Canvas(Modifier.size(96.dp).background(BACKGROUND).testTag(REFERENCE)) {
                    drawIntoCanvas { canvas ->
                        referenceRenderer.draw(
                            canvas.nativeCanvas,
                            size.width.roundToInt(),
                            size.height.roundToInt(),
                            referenceProgress,
                            referenceDarkTheme,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        // The first frame defines time zero. It must not advance to a nonzero animation phase.
        advanceFrames(1)
    }

    private fun advanceFrames(count: Int) {
        repeat(count) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    private fun assertFrame(progress: Float) {
        // Reference state is read during drawing, so this does not need another animation frame.
        rule.runOnIdle { referenceProgress = progress }
        rule.waitForIdle()
        capture(REFERENCE).useBitmap { expected ->
            capture(ACTUAL).useBitmap { actual ->
                assertEquivalent(expected, actual, "Expected animation progress $progress")
            }
        }
    }

    private fun assertChanged(before: Bitmap, message: String) {
        capture(ACTUAL).useBitmap { after ->
            assertTrue(message, meanChannelError(before, after) > 1.0)
        }
    }

    private fun assertEquivalent(expected: Bitmap, actual: Bitmap, message: String) {
        val error = meanChannelError(expected, actual)
        assertTrue("$message: mean channel error $error", error <= 0.15)
    }

    private fun meanChannelError(first: Bitmap, second: Bitmap): Double {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val size = first.width * first.height
        val a = IntArray(size)
        val b = IntArray(size)
        first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
        var error = 0L
        a.indices.forEach { index ->
            error += abs(AndroidColor.red(a[index]) - AndroidColor.red(b[index]))
            error += abs(AndroidColor.green(a[index]) - AndroidColor.green(b[index]))
            error += abs(AndroidColor.blue(a[index]) - AndroidColor.blue(b[index]))
            error += abs(AndroidColor.alpha(a[index]) - AndroidColor.alpha(b[index]))
        }
        return error.toDouble() / (size * 4)
    }

    private fun capture(tag: String): Bitmap =
        rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) = try {
        block(this)
    } finally {
        recycle()
    }

    private class TestMotionDurationScale : MotionDurationScale {
        var value by mutableFloatStateOf(1f)
        val reads = AtomicInteger()
        override val scaleFactor: Float
            get() {
                reads.incrementAndGet()
                return value
            }
    }

    private companion object {
        const val ACTUAL = "rearranging-boxes-animation"
        const val REFERENCE = "rearranging-boxes-reference"
        // LottieComposition truncates (109.99 - 13) / 60 * 1000 to whole milliseconds.
        const val DURATION_MILLIS = 1616f
        val BACKGROUND = Color(0xFF808080)
    }
}
