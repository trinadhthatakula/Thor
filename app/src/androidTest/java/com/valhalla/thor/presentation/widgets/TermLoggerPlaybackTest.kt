// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.R
import com.valhalla.thor.presentation.theme.ThorTheme
import com.valhalla.thor.util.UiText
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Harmless terminal fixture: no application services, operation queue or privilege access. */
@RunWith(AndroidJUnit4::class)
class TermLoggerPlaybackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>(
        // Own the test clock without changing the user's system animation preferences.
        ComposeUiTestConfig(
            effectContext = object : MotionDurationScale { override val scaleFactor = 1f },
        ),
    )

    @Test
    fun activePlaybackStopsAtSuccessAndRestartsWithoutLosingLogs() {
        rule.mainClock.autoAdvance = false
        val status = mutableStateOf(TermLoggerStatus.ACTIVE)
        var closes = 0
        val lines = listOf("Queued sample task", "Preparing preview", "Waiting for completion")
        rule.setContent {
            ThorTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                        TermLoggerContent(
                            title = UiText.DynamicString("Animation preview"),
                            logs = lines.map { UiText.DynamicString(it) },
                            status = status.value,
                            modifier = Modifier.height(260.dp).testTag(TERMINAL_TAG),
                            onClose = { closes += 1 }.takeIf { status.value == TermLoggerStatus.SUCCESS },
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(64)
        assertLogContent(lines)
        val close = rule.onNodeWithContentDescription(rule.activity.getString(R.string.cd_close))
        close.assertDoesNotExist()

        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(target.filesDir, "rearranging-boxes-parity/ui")
        check(output.isDirectory || output.mkdirs())
        val frameMetrics = mutableListOf("frame,status,compose_time_ms,pixel_sha256")
        var frame = 0
        fun captureFrames(count: Int, label: String): List<String> = (0 until count).map {
            rule.mainClock.advanceTimeBy(FRAME_STEP_MILLIS)
            val name = String.format(Locale.ROOT, "playback-%03d.png", frame)
            val hash = capture(output, name)
            frameMetrics += "$frame,$label,${rule.mainClock.currentTime},$hash"
            frame++
            hash
        }.also {
            File(output, "playback-frames.csv").writeText(frameMetrics.joinToString("\n", postfix = "\n"))
        }

        capture(output, "active-start.png")
        val activeHashes = captureFrames(18, "ACTIVE")
        assertTrue("The actual ACTIVE terminal indicator must animate", activeHashes.distinct().size >= 3)
        capture(output, "active-playing.png")

        rule.runOnIdle { status.value = TermLoggerStatus.SUCCESS }
        rule.mainClock.advanceTimeByFrame()
        close.assertIsDisplayed()
        assertLogContent(lines)
        val successHashes = captureFrames(4, "SUCCESS")
        assertEquals("SUCCESS must display a stable completion indicator", 1, successHashes.distinct().size)
        assertTrue("SUCCESS must replace the moving boxes", successHashes.first() !in activeHashes)
        capture(output, "success.png")
        close.performClick()
        rule.runOnIdle { assertEquals(1, closes) }

        rule.runOnIdle { status.value = TermLoggerStatus.ACTIVE }
        rule.mainClock.advanceTimeByFrame()
        close.assertDoesNotExist()
        assertLogContent(lines)
        capture(output, "active-restarted.png")
        val restartedHashes = captureFrames(18, "ACTIVE_RESTARTED")
        assertTrue("Returning to ACTIVE must restart moving boxes", restartedHashes.distinct().size >= 3)
        assertLogContent(lines)
        rule.runOnIdle { assertEquals("Playback must not invoke Close", 1, closes) }

        File(output, "playback-frames.csv").writeText(frameMetrics.joinToString("\n", postfix = "\n"))
        File(output, "playback.txt").writeText(
            "PASS: ACTIVE moves, SUCCESS is stable, ACTIVE restarts; log lines preserved; Close called once.\n" +
                "Screenshots are the real TermLoggerContent hosted by ComponentActivity with local fixture state.\n" +
                "No operation, app preference, application service or privilege action is invoked.\n" +
                "playback-%03d.png is a deterministic UI frame sequence, sampled every $FRAME_STEP_MILLIS ms.\n" +
                "Host video assembly can use approximately 10.416667 frames/second.\n" +
                "Frames=$frame; active unique=${activeHashes.distinct().size}; " +
                "success unique=${successHashes.distinct().size}; restarted unique=${restartedHashes.distinct().size}.\n",
        )
    }

    private fun assertLogContent(lines: List<String>) {
        rule.onNodeWithText("Animation preview").assertIsDisplayed()
        lines.forEachIndexed { index, text ->
            rule.onNodeWithTag(termLoggerLineTag(index)).assertIsDisplayed().assertTextEquals("> $text")
        }
    }

    private fun capture(directory: File, name: String): String {
        val bitmap = rule.onNodeWithTag(TERMINAL_TAG).captureToImage().asAndroidBitmap()
        try {
            File(directory, name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val buffer = ByteBuffer.allocate((pixels.size + 2) * Int.SIZE_BYTES)
                .putInt(bitmap.width).putInt(bitmap.height)
            pixels.forEach { buffer.putInt(it) }
            return MessageDigest.getInstance("SHA-256").digest(buffer.array())
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TERMINAL_TAG = "terminal-playback-fixture"
        const val FRAME_STEP_MILLIS = 96L
    }
}
